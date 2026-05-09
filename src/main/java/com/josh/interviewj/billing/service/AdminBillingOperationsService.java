package com.josh.interviewj.billing.service;

import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.dto.request.AdminManualAdjustmentRequest;
import com.josh.interviewj.billing.dto.request.AdminReviewBillingRefundRequest;
import com.josh.interviewj.billing.dto.response.AdminBillingRefundResponse;
import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminBillingOperationsService {

    private final Clock clock;
    private final UserRepository userRepository;
    private final BillingEventService billingEventService;
    private final CreditBalanceProjectionService creditBalanceProjectionService;
    private final AdminOperationLogService adminOperationLogService;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentOrderLifecycleService paymentOrderLifecycleService;
    private final BillingRefundService billingRefundService;

    @Transactional
    public Map<String, Object> createManualAdjustment(Long actorUserId, AdminManualAdjustmentRequest request) {
        User targetUser = userRepository.findByExternalId(request.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_003, "User not found"));
        BillingEvent adjustmentEvent = billingEventService.createOrGet(
                targetUser.getId(),
                BillingEventType.MANUAL_ADJUSTMENT,
                "ADMIN_MANUAL_ADJUSTMENT",
                request.getUserId().toString(),
                "manual-adjustment|" + targetUser.getId() + "|" + request.getReason() + "|" + request.getDeltaAmountMicros() + "|" + nowUtc(),
                request.getDeltaAmountMicros(),
                request.getBucketCode(),
                nowUtc(),
                mergeMetadata(request)
        );
        if (request.getBucketCode() == null || request.getBucketCode().isBlank()) {
            if (request.getDeltaAmountMicros() > 0) {
                creditBalanceProjectionService.grantPurchasedCredits(
                        targetUser.getId(),
                        adjustmentEvent,
                        request.getDeltaAmountMicros(),
                        null,
                        mergeMetadata(request)
                );
            } else {
                creditBalanceProjectionService.adjustWallet(targetUser.getId(), request.getDeltaAmountMicros());
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("billingEventId", adjustmentEvent.getId());
        response.put("targetUserId", request.getUserId().toString());
        response.put("deltaAmountMicros", request.getDeltaAmountMicros());
        response.put("bucketCode", request.getBucketCode());
        adminOperationLogService.recordCreate(
                actorUserId,
                AdminOperationResourceType.BILLING_MANUAL_ADJUSTMENT,
                String.valueOf(adjustmentEvent.getId()),
                request.getRequestId(),
                response,
                mergeMetadata(request)
        );
        return response;
    }

    private Map<String, Object> mergeMetadata(AdminManualAdjustmentRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", request.getReason());
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        return metadata;
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * Cancel a payment order and release any active reservation.
     *
     * @param actorUserId the admin user performing the action
     * @param orderId the payment order ID to cancel
     * @param reason the reason for cancellation
     * @return operation result
     */
    @Transactional
    public Map<String, Object> cancelOrder(Long actorUserId, Long orderId, String reason) {
        PaymentOrder order = paymentOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found"));

        paymentOrderLifecycleService.cancelOrder(orderId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderId", orderId);
        response.put("orderNo", order.getOrderNo());
        response.put("status", "CANCELED");
        response.put("reason", reason);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", reason);
        adminOperationLogService.recordCreate(
                actorUserId,
                AdminOperationResourceType.PAYMENT_ORDER,
                String.valueOf(orderId),
                "cancel-" + orderId,
                response,
                metadata
        );

        return response;
    }

    /**
     * Mark a payment order as failed and release any active reservation.
     *
     * @param actorUserId the admin user performing the action
     * @param orderId the payment order ID to mark as failed
     * @param reason the failure reason
     * @return operation result
     */
    @Transactional
    public Map<String, Object> markOrderFailed(Long actorUserId, Long orderId, String reason) {
        PaymentOrder order = paymentOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found"));

        paymentOrderLifecycleService.markOrderFailed(orderId, reason);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderId", orderId);
        response.put("orderNo", order.getOrderNo());
        response.put("status", "FAILED");
        response.put("reason", reason);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", reason);
        adminOperationLogService.recordCreate(
                actorUserId,
                AdminOperationResourceType.PAYMENT_ORDER,
                String.valueOf(orderId),
                "fail-" + orderId,
                response,
                metadata
        );

        return response;
    }

    public java.util.List<AdminBillingRefundResponse> listPendingRefundRequests() {
        return billingRefundService.listPendingReviewRequests();
    }

    @Transactional
    public AdminBillingRefundResponse reviewRefundRequest(
            Long actorUserId,
            Long refundRequestId,
            AdminReviewBillingRefundRequest request
    ) {
        return billingRefundService.reviewRefundRequest(actorUserId, refundRequestId, request);
    }
}
