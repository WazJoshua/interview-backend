package com.josh.interviewj.billing.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.dto.request.AdminReviewBillingRefundRequest;
import com.josh.interviewj.billing.dto.request.CreateBillingRefundRequest;
import com.josh.interviewj.billing.dto.response.AdminBillingRefundResponse;
import com.josh.interviewj.billing.dto.response.UserBillingRefundResponse;
import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.BillingRefundRequest;
import com.josh.interviewj.billing.model.BillingRefundStatus;
import com.josh.interviewj.billing.model.CreditLot;
import com.josh.interviewj.billing.model.CreditLotStatus;
import com.josh.interviewj.billing.model.PaymentEvent;
import com.josh.interviewj.billing.model.PaymentEventProcessStatus;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.provider.PaymentProviderAdapter;
import com.josh.interviewj.billing.provider.PaymentProviderRegistry;
import com.josh.interviewj.billing.provider.PaymentRefundRequest;
import com.josh.interviewj.billing.provider.PaymentRefundResult;
import com.josh.interviewj.billing.repository.BillingEventRepository;
import com.josh.interviewj.billing.repository.BillingRefundRequestRepository;
import com.josh.interviewj.billing.repository.CreditLotRepository;
import com.josh.interviewj.billing.repository.PaymentEventRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingRefundService {

    private static final Set<BillingRefundStatus> OPEN_REFUND_STATUSES = EnumSet.of(
            BillingRefundStatus.PENDING_REVIEW,
            BillingRefundStatus.APPROVED,
            BillingRefundStatus.REQUIRES_RECONCILIATION
    );

    private final Clock clock;
    private final UserRepository userRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final BillingRefundRequestRepository billingRefundRequestRepository;
    private final PaymentProviderRegistry paymentProviderRegistry;
    private final PaymentEventRepository paymentEventRepository;
    private final BillingEventRepository billingEventRepository;
    private final CreditLotRepository creditLotRepository;
    private final BillingRefundApplicationService billingRefundApplicationService;
    private final BillingReconciliationService billingReconciliationService;

    @Transactional
    public UserBillingRefundResponse createRefundRequest(String username, CreateBillingRefundRequest request) {
        User user = requireUser(username);
        PaymentOrder order = paymentOrderRepository.findByOrderNoAndUserId(request.getOrderNo(), user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found"));
        validateRefundableOrder(order);
        validateFullRefundAmount(request.getRequestedAmount(), order.getAmount());
        ensureNoOpenRefundRequest(order.getId());

        BillingRefundRequest refundRequest = BillingRefundRequest.builder()
                .externalId(UUID.randomUUID())
                .paymentOrderId(order.getId())
                .userId(user.getId())
                .requestedAmount(request.getRequestedAmount())
                .currency(order.getCurrency())
                .reason(request.getReason())
                .status(BillingRefundStatus.PENDING_REVIEW)
                .build();
        try {
            return toUserResponse(billingRefundRequestRepository.save(refundRequest), order);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Open refund request already exists", exception);
        }
    }

    public List<UserBillingRefundResponse> listCurrentUserRefundRequests(String username) {
        User user = requireUser(username);
        return billingRefundRequestRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId()).stream()
                .map(refundRequest -> toUserResponse(refundRequest, findOrder(refundRequest.getPaymentOrderId()).orElse(null)))
                .toList();
    }

    public List<AdminBillingRefundResponse> listPendingReviewRequests() {
        return billingRefundRequestRepository.findByStatusOrderByCreatedAtDescIdDesc(BillingRefundStatus.PENDING_REVIEW).stream()
                .map(refundRequest -> toAdminResponse(refundRequest, findOrder(refundRequest.getPaymentOrderId()).orElse(null)))
                .toList();
    }

    @Transactional
    public AdminBillingRefundResponse reviewRefundRequest(
            Long actorUserId,
            Long refundRequestId,
            AdminReviewBillingRefundRequest request
    ) {
        BillingRefundRequest refundRequest = billingRefundRequestRepository.findById(refundRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Billing refund request not found"));
        if (refundRequest.getStatus() != BillingRefundStatus.PENDING_REVIEW) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Billing refund request is not pending review");
        }

        ReviewDecision decision = parseDecision(request.getDecision());
        refundRequest.setReviewedByUserId(actorUserId);
        refundRequest.setReviewedAt(nowUtc());
        refundRequest.setReviewComment(request.getComment());

        if (decision == ReviewDecision.REJECT) {
            refundRequest.setStatus(BillingRefundStatus.REJECTED);
            return toAdminResponse(
                    billingRefundRequestRepository.save(refundRequest),
                    findOrder(refundRequest.getPaymentOrderId()).orElse(null)
            );
        }

        PaymentOrder order = paymentOrderRepository.findById(refundRequest.getPaymentOrderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found"));
        validateRefundableOrder(order);
        if (order.getOrderType() == PaymentOrderType.CREDIT_PURCHASE) {
            ensureCreditPurchaseFullyRefundable(order);
        }

        refundRequest.setStatus(BillingRefundStatus.APPROVED);
        PaymentProviderAdapter adapter = paymentProviderRegistry.requireAdapter(order.getProvider());
        PaymentRefundResult refundResult = adapter.refund(new PaymentRefundRequest(
                order.getOrderNo(),
                order.getProviderOrderRef(),
                refundRequest.getExternalId() == null ? String.valueOf(refundRequest.getId()) : refundRequest.getExternalId().toString(),
                refundRequest.getRequestedAmount(),
                refundRequest.getCurrency(),
                refundRequest.getReason()
        ));
        if (isSuccessfulRefundStatus(refundResult.status())) {
            billingRefundApplicationService.applyApprovedRefund(refundRequest, order, refundResult);
            return toAdminResponse(refundRequest, order);
        }

        refundRequest.setStatus(BillingRefundStatus.REQUIRES_RECONCILIATION);
        refundRequest.setProviderRefundRef(refundResult.providerRefundRef());
        refundRequest.setProviderStatus(refundResult.status());
        refundRequest.setRefundedAt(refundResult.completedAt());
        billingRefundRequestRepository.save(refundRequest);
        billingReconciliationService.createCase(
                order.getUserId(),
                order.getId(),
                null,
                order.getSubscriptionContractId(),
                "BILLING_REFUND_REQUEST",
                "REFUND_REQUIRES_REVIEW",
                Map.of(
                        "refundRequestId", refundRequest.getId(),
                        "providerStatus", refundResult.status() == null ? "UNKNOWN" : refundResult.status()
                )
        );
        return toAdminResponse(refundRequest, order);
    }

    private void ensureCreditPurchaseFullyRefundable(PaymentOrder order) {
        PaymentEvent paymentEvent = paymentEventRepository.findTopByPaymentOrderIdAndProcessStatusOrderByOccurredAtDescIdDesc(
                        order.getId(),
                        PaymentEventProcessStatus.APPLIED
                )
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Applied payment event not found"));
        BillingEvent grantEvent = billingEventRepository.findFirstByUserIdAndEventTypeAndSourceTypeAndSourceIdOrderByOccurredAtDescIdDesc(
                        order.getUserId(),
                        BillingEventType.CREDIT_PURCHASE_GRANTED,
                        "PAYMENT_EVENT",
                        paymentEvent.getProviderEventId()
                )
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Credit grant billing event not found"));
        CreditLot creditLot = creditLotRepository.findBySourceBillingEventId(grantEvent.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Granted credit lot not found"));
        if (creditLot.getStatus() != CreditLotStatus.ACTIVE
                || !creditLot.getOriginalAmountMicros().equals(creditLot.getRemainingAmountMicros())) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Credit purchase has already been partially consumed");
        }
    }

    private void validateRefundableOrder(PaymentOrder order) {
        if (order.getStatus() != PaymentOrderStatus.SUCCEEDED) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Only succeeded payment orders can be refunded");
        }
    }

    private void validateFullRefundAmount(BigDecimal requestedAmount, BigDecimal originalAmount) {
        if (requestedAmount == null
                || requestedAmount.signum() <= 0
                || originalAmount == null
                || requestedAmount.compareTo(originalAmount) != 0) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Only full refunds are supported");
        }
    }

    private void ensureNoOpenRefundRequest(Long paymentOrderId) {
        if (billingRefundRequestRepository.findFirstByPaymentOrderIdAndStatusInOrderByCreatedAtDescIdDesc(
                paymentOrderId,
                OPEN_REFUND_STATUSES
        ).isPresent()) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Open refund request already exists");
        }
    }

    private ReviewDecision parseDecision(String rawDecision) {
        String normalized = rawDecision == null ? "" : rawDecision.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "APPROVE" -> ReviewDecision.APPROVE;
            case "REJECT" -> ReviewDecision.REJECT;
            default -> throw new BusinessException(ErrorCode.PAYMENT_004, "Unsupported refund review decision");
        };
    }

    private boolean isSuccessfulRefundStatus(String rawStatus) {
        String normalized = rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("SUCCESS") || normalized.contains("SUCCEEDED");
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003, "User not found"));
    }

    private Optional<PaymentOrder> findOrder(Long paymentOrderId) {
        return paymentOrderId == null ? Optional.empty() : paymentOrderRepository.findById(paymentOrderId);
    }

    private UserBillingRefundResponse toUserResponse(BillingRefundRequest refundRequest, PaymentOrder order) {
        return UserBillingRefundResponse.builder()
                .id(stringify(refundRequest.getId()))
                .orderNo(order == null ? null : order.getOrderNo())
                .requestedAmount(formatAmount(refundRequest.getRequestedAmount()))
                .currency(refundRequest.getCurrency())
                .reason(refundRequest.getReason())
                .status(refundRequest.getStatus() == null ? null : refundRequest.getStatus().name())
                .reviewComment(refundRequest.getReviewComment())
                .providerRefundRef(refundRequest.getProviderRefundRef())
                .providerStatus(refundRequest.getProviderStatus())
                .reviewedAt(toOffset(refundRequest.getReviewedAt()))
                .refundedAt(toOffset(refundRequest.getRefundedAt()))
                .createdAt(toOffset(refundRequest.getCreatedAt()))
                .updatedAt(toOffset(refundRequest.getUpdatedAt()))
                .build();
    }

    private AdminBillingRefundResponse toAdminResponse(BillingRefundRequest refundRequest, PaymentOrder order) {
        return AdminBillingRefundResponse.builder()
                .id(stringify(refundRequest.getId()))
                .userId(stringify(refundRequest.getUserId()))
                .orderNo(order == null ? null : order.getOrderNo())
                .requestedAmount(formatAmount(refundRequest.getRequestedAmount()))
                .currency(refundRequest.getCurrency())
                .reason(refundRequest.getReason())
                .status(refundRequest.getStatus() == null ? null : refundRequest.getStatus().name())
                .reviewComment(refundRequest.getReviewComment())
                .providerRefundRef(refundRequest.getProviderRefundRef())
                .providerStatus(refundRequest.getProviderStatus())
                .reviewedAt(toOffset(refundRequest.getReviewedAt()))
                .refundedAt(toOffset(refundRequest.getRefundedAt()))
                .createdAt(toOffset(refundRequest.getCreatedAt()))
                .updatedAt(toOffset(refundRequest.getUpdatedAt()))
                .build();
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private String formatAmount(BigDecimal amount) {
        return amount == null ? null : amount.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private enum ReviewDecision {
        APPROVE,
        REJECT
    }
}
