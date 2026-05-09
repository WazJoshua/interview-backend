package com.josh.interviewj.billing.service;

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
import com.josh.interviewj.billing.provider.PaymentRefundResult;
import com.josh.interviewj.billing.provider.VerifiedPaymentNotification;
import com.josh.interviewj.billing.repository.BillingEventRepository;
import com.josh.interviewj.billing.repository.BillingRefundRequestRepository;
import com.josh.interviewj.billing.repository.CreditLotRepository;
import com.josh.interviewj.billing.repository.PaymentEventRepository;
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
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BillingRefundApplicationService {

    private final Clock clock;
    private final BillingEventService billingEventService;
    private final CreditBalanceProjectionService creditBalanceProjectionService;
    private final CreditLotRepository creditLotRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final BillingEventRepository billingEventRepository;
    private final BillingRefundRequestRepository billingRefundRequestRepository;
    private final BillingReconciliationService billingReconciliationService;
    private final PaymentOrderRepository paymentOrderRepository;

    @Transactional
    public void applyApprovedRefund(
            BillingRefundRequest refundRequest,
            PaymentOrder order,
            PaymentRefundResult refundResult
    ) {
        LocalDateTime refundedAt = refundResult.completedAt() == null ? nowUtc() : refundResult.completedAt();
        refundRequest.setStatus(BillingRefundStatus.REFUNDED);
        refundRequest.setProviderRefundRef(refundResult.providerRefundRef());
        refundRequest.setProviderStatus(refundResult.status());
        refundRequest.setRefundedAt(refundedAt);

        long deltaAmountMicros = 0L;
        if (order.getOrderType() == PaymentOrderType.CREDIT_PURCHASE) {
            CreditLot lot = requireGrantedCreditLot(order);
            deltaAmountMicros = lot.getOriginalAmountMicros();
            lot.setRemainingAmountMicros(0L);
            lot.setStatus(CreditLotStatus.REVERSED);
            creditLotRepository.save(lot);
            creditBalanceProjectionService.adjustWallet(order.getUserId(), -deltaAmountMicros);
        } else {
            order.setStatus(PaymentOrderStatus.REQUIRES_RECONCILIATION);
            paymentOrderRepository.save(order);
            billingReconciliationService.createCase(
                    order.getUserId(),
                    order.getId(),
                    null,
                    order.getSubscriptionContractId(),
                    "BILLING_REFUND_REQUEST",
                    "SUBSCRIPTION_REFUND_REQUIRES_REVIEW",
                    buildDetails(
                            "orderNo", order.getOrderNo(),
                            "refundRequestId", refundRequest.getId(),
                            "providerRefundRef", refundResult.providerRefundRef(),
                            "providerStatus", refundResult.status()
                    )
            );
        }

        billingEventService.createOrGet(
                order.getUserId(),
                BillingEventType.PAYMENT_REFUNDED,
                "BILLING_REFUND_REQUEST",
                String.valueOf(refundRequest.getId()),
                "refund|" + refundRequest.getId(),
                -deltaAmountMicros,
                null,
                refundedAt,
                buildDetails(
                        "orderNo", order.getOrderNo(),
                        "providerRefundRef", refundResult.providerRefundRef(),
                        "providerStatus", refundResult.status()
                )
        );
        billingRefundRequestRepository.save(refundRequest);
    }

    @Transactional
    public void handleProviderReversal(
            PaymentOrder order,
            PaymentEvent paymentEvent,
            VerifiedPaymentNotification notification
    ) {
        billingEventService.createOrGet(
                order.getUserId(),
                resolveReversalBillingEventType(notification.eventType()),
                "PAYMENT_EVENT",
                paymentEvent.getProviderEventId(),
                "reversal|" + paymentEvent.getProviderEventId(),
                0L,
                null,
                notification.occurredAt(),
                buildDetails(
                        "orderNo", order.getOrderNo(),
                        "eventType", notification.eventType(),
                        "provider", notification.provider()
                )
        );
        order.setStatus(PaymentOrderStatus.REQUIRES_RECONCILIATION);
        paymentOrderRepository.save(order);
        billingReconciliationService.createCase(
                order.getUserId(),
                order.getId(),
                paymentEvent.getId(),
                order.getSubscriptionContractId(),
                "PAYMENT_EVENT",
                "REVERSAL_EVENT_REQUIRES_REVIEW",
                buildDetails(
                        "orderNo", order.getOrderNo(),
                        "eventType", notification.eventType(),
                        "providerEventId", paymentEvent.getProviderEventId()
                )
        );
    }

    private CreditLot requireGrantedCreditLot(PaymentOrder order) {
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
        return creditLotRepository.findBySourceBillingEventId(grantEvent.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Granted credit lot not found"));
    }

    private BillingEventType resolveReversalBillingEventType(String rawEventType) {
        String normalized = rawEventType == null ? "" : rawEventType.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("CHARGEBACK")) {
            return BillingEventType.PAYMENT_CHARGEBACK;
        }
        return BillingEventType.PAYMENT_REFUNDED;
    }

    private Map<String, Object> buildDetails(Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            Object key = keyValues[index];
            Object value = keyValues[index + 1];
            if (key != null && value != null) {
                details.put(String.valueOf(key), value);
            }
        }
        return details;
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
