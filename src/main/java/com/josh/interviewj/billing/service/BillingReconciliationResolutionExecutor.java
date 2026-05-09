package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.BillingReconciliationCase;
import com.josh.interviewj.billing.model.CreditLot;
import com.josh.interviewj.billing.model.CreditLotStatus;
import com.josh.interviewj.billing.model.PaymentEvent;
import com.josh.interviewj.billing.model.PaymentEventProcessStatus;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.repository.BillingEventRepository;
import com.josh.interviewj.billing.repository.CreditLotRepository;
import com.josh.interviewj.billing.repository.PaymentEventRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
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
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingReconciliationResolutionExecutor {

    private final Clock clock;
    private final BillingSnapshotCodec billingSnapshotCodec;
    private final BillingEventService billingEventService;
    private final CreditBalanceProjectionService creditBalanceProjectionService;
    private final CreditLotRepository creditLotRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final BillingEventRepository billingEventRepository;
    private final SubscriptionContractRepository subscriptionContractRepository;
    private final SubscriptionQuotaGrantService subscriptionQuotaGrantService;
    private final LegacyCreditPolicyProjectionService legacyCreditPolicyProjectionService;
    private final SubscriptionRenewalOrderService subscriptionRenewalOrderService;

    @Transactional
    public void execute(BillingReconciliationCase reconciliationCase, String resolutionCode, Map<String, Object> metadata) {
        ResolutionCode code = parseResolutionCode(resolutionCode);
        switch (code) {
            case FULFILL_MANUALLY -> fulfillManually(reconciliationCase, metadata);
            case REFUND_MANUALLY -> refundManually(reconciliationCase, metadata);
            case CLOSE_NO_ACTION -> {
                // Explicit no-op.
            }
        }
    }

    private void fulfillManually(BillingReconciliationCase reconciliationCase, Map<String, Object> metadata) {
        PaymentOrder order = requirePaymentOrder(reconciliationCase.getPaymentOrderId());
        switch (order.getOrderType()) {
            case CREDIT_PURCHASE -> fulfillCreditPurchase(order, reconciliationCase, metadata);
            case SUBSCRIPTION_PURCHASE -> fulfillSubscriptionPurchase(order, reconciliationCase, metadata);
            case SUBSCRIPTION_RENEWAL -> fulfillSubscriptionRenewal(order, reconciliationCase, metadata);
        }
        order.setStatus(PaymentOrderStatus.SUCCEEDED);
        paymentOrderRepository.save(order);
    }

    private void refundManually(BillingReconciliationCase reconciliationCase, Map<String, Object> metadata) {
        PaymentOrder order = requirePaymentOrder(reconciliationCase.getPaymentOrderId());
        long deltaAmountMicros = 0L;
        CreditLot grantedLot = findGrantedCreditLot(order).orElse(null);
        if (grantedLot != null && grantedLot.getStatus() == CreditLotStatus.ACTIVE && grantedLot.getRemainingAmountMicros() > 0) {
            deltaAmountMicros = grantedLot.getOriginalAmountMicros();
            grantedLot.setRemainingAmountMicros(0L);
            grantedLot.setStatus(CreditLotStatus.REVERSED);
            creditLotRepository.save(grantedLot);
            creditBalanceProjectionService.adjustWallet(order.getUserId(), -deltaAmountMicros);
        }
        billingEventService.createOrGet(
                order.getUserId(),
                BillingEventType.PAYMENT_REFUNDED,
                "BILLING_RECONCILIATION_CASE",
                String.valueOf(reconciliationCase.getId()),
                "reconciliation|" + reconciliationCase.getId() + "|manual-refund",
                -deltaAmountMicros,
                null,
                nowUtc(),
                mergeDetails(
                        reconciliationCase,
                        metadata,
                        "orderNo", order.getOrderNo(),
                        "caseId", reconciliationCase.getId()
                )
        );
    }

    private void fulfillCreditPurchase(
            PaymentOrder order,
            BillingReconciliationCase reconciliationCase,
            Map<String, Object> metadata
    ) {
        PricingSnapshot pricingSnapshot = billingSnapshotCodec.readPricingSnapshot(order.getPricingSnapshot());
        if (pricingSnapshot.creditsAmountMicros() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_005, "Credit purchase snapshot is incomplete");
        }
        BillingEvent grantEvent = billingEventService.createOrGet(
                order.getUserId(),
                BillingEventType.CREDIT_PURCHASE_GRANTED,
                "BILLING_RECONCILIATION_CASE",
                String.valueOf(reconciliationCase.getId()),
                "reconciliation|" + reconciliationCase.getId() + "|credit-grant",
                pricingSnapshot.creditsAmountMicros(),
                null,
                nowUtc(),
                mergeDetails(
                        reconciliationCase,
                        metadata,
                        "orderNo", order.getOrderNo(),
                        "caseId", reconciliationCase.getId()
                )
        );
        creditBalanceProjectionService.grantPurchasedCredits(
                order.getUserId(),
                grantEvent,
                pricingSnapshot.creditsAmountMicros(),
                null,
                Map.of(
                        "orderNo", order.getOrderNo(),
                        "reconciliationCaseId", reconciliationCase.getId()
                )
        );
    }

    private void fulfillSubscriptionPurchase(
            PaymentOrder order,
            BillingReconciliationCase reconciliationCase,
            Map<String, Object> metadata
    ) {
        PricingSnapshot pricingSnapshot = billingSnapshotCodec.readPricingSnapshot(order.getPricingSnapshot());
        if (pricingSnapshot.billingPlanId() == null || pricingSnapshot.billingPlanVersionId() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_005, "Subscription order snapshot is incomplete");
        }
        LocalDateTime periodStart = order.getPaidAt() == null ? nowUtc() : order.getPaidAt();
        LocalDateTime periodEnd = subscriptionRenewalOrderService.calculatePeriodEnd(periodStart, pricingSnapshot.billingCycle());
        BillingEvent activationEvent = billingEventService.createOrGet(
                order.getUserId(),
                BillingEventType.SUBSCRIPTION_ACTIVATED,
                "BILLING_RECONCILIATION_CASE",
                String.valueOf(reconciliationCase.getId()),
                "reconciliation|" + reconciliationCase.getId() + "|subscription-activated",
                0L,
                null,
                nowUtc(),
                mergeDetails(
                        reconciliationCase,
                        metadata,
                        "orderNo", order.getOrderNo(),
                        "caseId", reconciliationCase.getId()
                )
        );
        SubscriptionContract contract = order.getSubscriptionContractId() == null
                ? subscriptionContractRepository.findOpenContractByUserId(order.getUserId()).orElse(null)
                : subscriptionContractRepository.findById(order.getSubscriptionContractId()).orElse(null);
        if (contract == null) {
            contract = SubscriptionContract.builder()
                    .externalId(UUID.randomUUID())
                    .userId(order.getUserId())
                    .billingPlanId(pricingSnapshot.billingPlanId())
                    .billingPlanVersionId(pricingSnapshot.billingPlanVersionId())
                    .provider(order.getProvider())
                    .status(SubscriptionContractStatus.ACTIVE)
                    .currentPeriodStart(periodStart)
                    .currentPeriodEnd(periodEnd)
                    .cancelAtPeriodEnd(false)
                    .metadata(billingSnapshotCodec.write(Map.of(
                            "sourceOrderNo", order.getOrderNo(),
                            "reconciliationCaseId", reconciliationCase.getId()
                    )))
                    .build();
        } else {
            contract.setBillingPlanId(pricingSnapshot.billingPlanId());
            contract.setBillingPlanVersionId(pricingSnapshot.billingPlanVersionId());
            contract.setProvider(order.getProvider());
            contract.setStatus(SubscriptionContractStatus.ACTIVE);
            contract.setCurrentPeriodStart(periodStart);
            contract.setCurrentPeriodEnd(periodEnd);
            contract.setCancelAtPeriodEnd(false);
        }
        contract = subscriptionContractRepository.save(contract);
        order.setSubscriptionContractId(contract.getId());
        paymentOrderRepository.save(order);

        BillingEvent grantEvent = billingEventService.createOrGet(
                order.getUserId(),
                BillingEventType.SUBSCRIPTION_QUOTA_GRANTED,
                "BILLING_RECONCILIATION_CASE",
                String.valueOf(reconciliationCase.getId()),
                "reconciliation|" + reconciliationCase.getId() + "|subscription-grant|" + periodStart,
                0L,
                null,
                nowUtc(),
                mergeDetails(
                        reconciliationCase,
                        metadata,
                        "subscriptionContractId", contract.getId(),
                        "periodStart", periodStart.toString(),
                        "periodEnd", periodEnd.toString()
                )
        );
        subscriptionQuotaGrantService.createGrants(
                contract.getId(),
                periodStart,
                periodEnd,
                grantEvent,
                billingSnapshotCodec.readEntitlementSnapshot(order.getEntitlementSnapshot())
        );
        legacyCreditPolicyProjectionService.projectForUser(order.getUserId());
        subscriptionRenewalOrderService.ensureNextRenewalOrder(contract);
        paymentOrderRepository.save(order);
    }

    private void fulfillSubscriptionRenewal(
            PaymentOrder order,
            BillingReconciliationCase reconciliationCase,
            Map<String, Object> metadata
    ) {
        if (order.getSubscriptionContractId() == null
                || order.getRenewalPeriodStart() == null
                || order.getRenewalPeriodEnd() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_005, "Renewal order snapshot is incomplete");
        }
        PricingSnapshot pricingSnapshot = billingSnapshotCodec.readPricingSnapshot(order.getPricingSnapshot());
        SubscriptionContract contract = subscriptionContractRepository.findById(order.getSubscriptionContractId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_005, "Subscription contract not found for renewal"));
        billingEventService.createOrGet(
                order.getUserId(),
                BillingEventType.SUBSCRIPTION_RENEWED,
                "BILLING_RECONCILIATION_CASE",
                String.valueOf(reconciliationCase.getId()),
                "reconciliation|" + reconciliationCase.getId() + "|subscription-renewed",
                0L,
                null,
                nowUtc(),
                mergeDetails(
                        reconciliationCase,
                        metadata,
                        "orderNo", order.getOrderNo(),
                        "subscriptionContractId", contract.getId()
                )
        );
        contract.setBillingPlanVersionId(pricingSnapshot.billingPlanVersionId());
        contract.setStatus(SubscriptionContractStatus.ACTIVE);
        contract.setCurrentPeriodStart(order.getRenewalPeriodStart());
        contract.setCurrentPeriodEnd(order.getRenewalPeriodEnd());
        contract = subscriptionContractRepository.save(contract);

        BillingEvent grantEvent = billingEventService.createOrGet(
                order.getUserId(),
                BillingEventType.SUBSCRIPTION_QUOTA_GRANTED,
                "BILLING_RECONCILIATION_CASE",
                String.valueOf(reconciliationCase.getId()),
                "reconciliation|" + reconciliationCase.getId() + "|subscription-grant|" + order.getRenewalPeriodStart(),
                0L,
                null,
                nowUtc(),
                mergeDetails(
                        reconciliationCase,
                        metadata,
                        "subscriptionContractId", contract.getId(),
                        "periodStart", order.getRenewalPeriodStart().toString(),
                        "periodEnd", order.getRenewalPeriodEnd().toString()
                )
        );
        subscriptionQuotaGrantService.createGrants(
                contract.getId(),
                order.getRenewalPeriodStart(),
                order.getRenewalPeriodEnd(),
                grantEvent,
                billingSnapshotCodec.readEntitlementSnapshot(order.getEntitlementSnapshot())
        );
        legacyCreditPolicyProjectionService.projectForUser(order.getUserId());
        subscriptionRenewalOrderService.ensureNextRenewalOrder(contract);
    }

    private PaymentOrder requirePaymentOrder(Long paymentOrderId) {
        if (paymentOrderId == null) {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_004, "Billing reconciliation case is missing payment order");
        }
        return paymentOrderRepository.findById(paymentOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found"));
    }

    private java.util.Optional<CreditLot> findGrantedCreditLot(PaymentOrder order) {
        if (order.getOrderType() != PaymentOrderType.CREDIT_PURCHASE) {
            return java.util.Optional.empty();
        }
        PaymentEvent paymentEvent = paymentEventRepository.findTopByPaymentOrderIdAndProcessStatusOrderByOccurredAtDescIdDesc(
                order.getId(),
                PaymentEventProcessStatus.APPLIED
        ).orElse(null);
        if (paymentEvent == null) {
            return java.util.Optional.empty();
        }
        BillingEvent grantEvent = billingEventRepository.findFirstByUserIdAndEventTypeAndSourceTypeAndSourceIdOrderByOccurredAtDescIdDesc(
                order.getUserId(),
                BillingEventType.CREDIT_PURCHASE_GRANTED,
                "PAYMENT_EVENT",
                paymentEvent.getProviderEventId()
        ).orElse(null);
        if (grantEvent == null) {
            return java.util.Optional.empty();
        }
        return creditLotRepository.findBySourceBillingEventId(grantEvent.getId());
    }

    private Map<String, Object> mergeDetails(
            BillingReconciliationCase reconciliationCase,
            Map<String, Object> metadata,
            Object... additionalKeyValues
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (reconciliationCase.getDetails() != null && !reconciliationCase.getDetails().isBlank()) {
            details.putAll(billingSnapshotCodec.readMap(reconciliationCase.getDetails()));
        }
        if (metadata != null) {
            details.putAll(metadata);
        }
        for (int index = 0; index < additionalKeyValues.length; index += 2) {
            Object key = additionalKeyValues[index];
            Object value = additionalKeyValues[index + 1];
            if (key != null && value != null) {
                details.put(String.valueOf(key), value);
            }
        }
        return details;
    }

    private ResolutionCode parseResolutionCode(String rawValue) {
        String normalized = rawValue == null ? "" : rawValue.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FULFILL_MANUALLY" -> ResolutionCode.FULFILL_MANUALLY;
            case "REFUND_MANUALLY" -> ResolutionCode.REFUND_MANUALLY;
            case "CLOSE_NO_ACTION" -> ResolutionCode.CLOSE_NO_ACTION;
            default -> throw new BusinessException(ErrorCode.ADMIN_BILLING_004, "Unsupported reconciliation resolution code");
        };
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private enum ResolutionCode {
        FULFILL_MANUALLY,
        REFUND_MANUALLY,
        CLOSE_NO_ACTION
    }
}
