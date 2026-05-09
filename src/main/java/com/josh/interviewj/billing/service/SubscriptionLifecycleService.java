package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.BillingPlanEntitlementItem;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.InventoryConfirmationResult;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.provider.VerifiedPaymentNotification;
import com.josh.interviewj.billing.repository.BillingPlanEntitlementItemRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionLifecycleService {

    private final BillingSnapshotCodec billingSnapshotCodec;
    private final BillingEventService billingEventService;
    private final InventoryReservationService inventoryReservationService;
    private final LegacyCreditPolicyProjectionService legacyCreditPolicyProjectionService;
    private final SubscriptionQuotaGrantService subscriptionQuotaGrantService;
    private final SubscriptionRenewalOrderService subscriptionRenewalOrderService;
    private final SubscriptionContractRepository subscriptionContractRepository;
    private final BillingPlanVersionRepository billingPlanVersionRepository;
    private final BillingPlanEntitlementItemRepository entitlementItemRepository;
    private final Clock clock;

    /**
     * Result of applying a subscription purchase or renewal.
     * Used to signal whether fulfillment completed or requires reconciliation.
     */
    public enum SubscriptionFulfillmentResult {
        /**
         * Subscription contract created/updated successfully.
         */
        FULFILLED,
        /**
         * Order requires reconciliation (e.g., post-cutover missing reservation).
         * No fulfillment side effects were created.
         */
        REQUIRES_RECONCILIATION
    }

    /**
     * Apply subscription purchase order after successful payment.
     *
     * IMPORTANT: Inventory confirmation is performed BEFORE any fulfillment side effects
     * (billing events, subscription contracts, quota grants) to avoid partial commits.
     *
     * @param order the payment order
     * @param notification the verified payment notification
     * @return SubscriptionContract if fulfillment succeeded, null if order requires reconciliation
     */
    @Transactional
    public SubscriptionContract applySubscriptionPurchase(PaymentOrder order, VerifiedPaymentNotification notification) {
        PricingSnapshot pricingSnapshot = billingSnapshotCodec.readPricingSnapshot(order.getPricingSnapshot());
        if (pricingSnapshot.billingPlanId() == null || pricingSnapshot.billingPlanVersionId() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_005, "Subscription order snapshot is incomplete");
        }

        // IMPORTANT: Confirm inventory reservation BEFORE any fulfillment side effects
        // to avoid partial commits when post-cutover order lacks reservation
        if (order.getLockedPlanVersionId() != null) {
            BillingPlanVersion version = billingPlanVersionRepository.findById(pricingSnapshot.billingPlanVersionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_BILLING_003, "Billing plan version not found"));
            InventoryConfirmationResult confirmationResult = inventoryReservationService.confirmForOrder(
                    order.getId(),
                    order.getLockedPlanVersionId(),
                    version,
                    order.getCreatedAt(),
                    order.getUserId(),
                    order.getOrderNo()
            );
            // If post-cutover order lacks reservation, return null to signal reconciliation needed
            // The POST_CUTOVER_ORDER_WITHOUT_RESERVATION event is already recorded by confirmForOrder
            if (confirmationResult == InventoryConfirmationResult.REQUIRES_RECONCILIATION) {
                return null; // Signal to caller that order requires reconciliation
            }
        }

        LocalDateTime periodStart = notification.occurredAt();
        LocalDateTime periodEnd = subscriptionRenewalOrderService.calculatePeriodEnd(periodStart, pricingSnapshot.billingCycle());

        BillingEvent activationEvent = billingEventService.createOrGet(
                order.getUserId(),
                BillingEventType.SUBSCRIPTION_ACTIVATED,
                "PAYMENT_EVENT",
                notification.providerEventId(),
                notification.provider() + "|" + notification.providerEventId() + "|subscription-activated",
                0L,
                null,
                notification.occurredAt(),
                Map.of("orderNo", order.getOrderNo(), "providerOrderRef", notification.providerOrderRef())
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
                    .providerSubscriptionRef(notification.providerSubscriptionRef())
                    .status(SubscriptionContractStatus.ACTIVE)
                    .currentPeriodStart(periodStart)
                    .currentPeriodEnd(periodEnd)
                    .cancelAtPeriodEnd(false)
                    .metadata(billingSnapshotCodec.write(Map.of("sourceOrderNo", order.getOrderNo())))
                    .build();
        } else {
            contract.setBillingPlanId(pricingSnapshot.billingPlanId());
            contract.setBillingPlanVersionId(pricingSnapshot.billingPlanVersionId());
            contract.setProvider(order.getProvider());
            contract.setProviderSubscriptionRef(notification.providerSubscriptionRef());
            contract.setStatus(SubscriptionContractStatus.ACTIVE);
            contract.setCurrentPeriodStart(periodStart);
            contract.setCurrentPeriodEnd(periodEnd);
        }
        contract = subscriptionContractRepository.save(contract);
        order.setSubscriptionContractId(contract.getId());

        BillingEvent grantEvent = billingEventService.createOrGet(
                order.getUserId(),
                BillingEventType.SUBSCRIPTION_QUOTA_GRANTED,
                "BILLING_EVENT",
                String.valueOf(activationEvent.getId()),
                notification.provider() + "|" + notification.providerEventId() + "|subscription-grant|" + periodStart,
                0L,
                null,
                notification.occurredAt(),
                Map.of("subscriptionContractId", contract.getId(), "periodStart", periodStart.toString(), "periodEnd", periodEnd.toString())
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
        return contract;
    }

    /**
     * Apply subscription renewal order after successful payment.
     *
     * IMPORTANT: Inventory confirmation is performed BEFORE any fulfillment side effects
     * (billing events, subscription contracts, quota grants) to avoid partial commits.
     *
     * @param order the payment order
     * @param notification the verified payment notification
     * @return SubscriptionContract if fulfillment succeeded, null if order requires reconciliation
     */
    @Transactional
    public SubscriptionContract applySubscriptionRenewal(PaymentOrder order, VerifiedPaymentNotification notification) {
        if (order.getSubscriptionContractId() == null
                || order.getRenewalPeriodStart() == null
                || order.getRenewalPeriodEnd() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_005, "Renewal order snapshot is incomplete");
        }
        PricingSnapshot pricingSnapshot = billingSnapshotCodec.readPricingSnapshot(order.getPricingSnapshot());

        // IMPORTANT: Confirm inventory reservation BEFORE any fulfillment side effects
        // to avoid partial commits when post-cutover order lacks reservation
        if (order.getLockedPlanVersionId() != null) {
            BillingPlanVersion version = billingPlanVersionRepository.findById(pricingSnapshot.billingPlanVersionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_BILLING_003, "Billing plan version not found"));
            InventoryConfirmationResult confirmationResult = inventoryReservationService.confirmForOrder(
                    order.getId(),
                    order.getLockedPlanVersionId(),
                    version,
                    order.getCreatedAt(),
                    order.getUserId(),
                    order.getOrderNo()
            );
            // If post-cutover order lacks reservation, return null to signal reconciliation needed
            // The POST_CUTOVER_ORDER_WITHOUT_RESERVATION event is already recorded by confirmForOrder
            if (confirmationResult == InventoryConfirmationResult.REQUIRES_RECONCILIATION) {
                return null; // Signal to caller that order requires reconciliation
            }
        }

        SubscriptionContract contract = subscriptionContractRepository.findById(order.getSubscriptionContractId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_005, "Subscription contract not found for renewal"));
        BillingEvent renewalEvent = billingEventService.createOrGet(
                order.getUserId(),
                BillingEventType.SUBSCRIPTION_RENEWED,
                "PAYMENT_EVENT",
                notification.providerEventId(),
                notification.provider() + "|" + notification.providerEventId() + "|subscription-renewed",
                0L,
                null,
                notification.occurredAt(),
                Map.of("orderNo", order.getOrderNo(), "subscriptionContractId", contract.getId())
        );
        contract.setBillingPlanVersionId(pricingSnapshot.billingPlanVersionId());
        contract.setStatus(SubscriptionContractStatus.ACTIVE);
        contract.setCurrentPeriodStart(order.getRenewalPeriodStart());
        contract.setCurrentPeriodEnd(order.getRenewalPeriodEnd());
        if (contract.getNextPlanVersionId() != null
                && contract.getNextPlanVersionId().equals(pricingSnapshot.billingPlanVersionId())) {
            contract.setNextPlanVersionId(null);
        }
        contract.setProviderSubscriptionRef(notification.providerSubscriptionRef());
        contract = subscriptionContractRepository.save(contract);

        BillingEvent grantEvent = billingEventService.createOrGet(
                order.getUserId(),
                BillingEventType.SUBSCRIPTION_QUOTA_GRANTED,
                "BILLING_EVENT",
                String.valueOf(renewalEvent.getId()),
                notification.provider() + "|" + notification.providerEventId() + "|subscription-grant|" + order.getRenewalPeriodStart(),
                0L,
                null,
                notification.occurredAt(),
                Map.of("subscriptionContractId", contract.getId(), "periodStart", order.getRenewalPeriodStart().toString(), "periodEnd", order.getRenewalPeriodEnd().toString())
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
        return contract;
    }

    @Transactional
    public SubscriptionContract applyActivationCodeSubscription(
            Long userId,
            Long billingPlanVersionId,
            int durationDays,
            String activationCodeId
    ) {
        BillingPlanVersion version = billingPlanVersionRepository.findById(billingPlanVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_BILLING_003, "Plan version not found"));
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDateTime periodEnd = now.plusDays(durationDays);

        String idempotencyKey = "ACTIVATION_CODE|" + activationCodeId + "|subscription-granted";
        BillingEvent grantEvent = billingEventService.createOrGet(
                userId,
                BillingEventType.ACTIVATION_CODE_SUBSCRIPTION_GRANTED,
                "ACTIVATION_CODE",
                activationCodeId,
                idempotencyKey,
                0L,
                null,
                now,
                Map.of(
                        "activationCodeId", activationCodeId,
                        "planVersionId", billingPlanVersionId,
                        "durationDays", durationDays
                )
        );

        SubscriptionContract contract = subscriptionContractRepository.save(
                SubscriptionContract.builder()
                        .externalId(UUID.randomUUID())
                        .userId(userId)
                        .billingPlanId(version.getBillingPlanId())
                        .billingPlanVersionId(billingPlanVersionId)
                        .provider("ACTIVATION_CODE")
                        .status(SubscriptionContractStatus.ACTIVE)
                        .currentPeriodStart(now)
                        .currentPeriodEnd(periodEnd)
                        .cancelAtPeriodEnd(false)
                        .autoRenew(false)
                        .build()
        );

        String quotaGrantKey = "ACTIVATION_CODE|" + activationCodeId + "|quota-grant|" + now;
        BillingEvent quotaEvent = billingEventService.createOrGet(
                userId,
                BillingEventType.SUBSCRIPTION_QUOTA_GRANTED,
                "BILLING_EVENT",
                String.valueOf(grantEvent.getId()),
                quotaGrantKey,
                0L,
                null,
                now,
                Map.of(
                        "subscriptionContractId", contract.getId(),
                        "periodStart", now.toString(),
                        "periodEnd", periodEnd.toString()
                )
        );

        List<EntitlementSnapshotItem> snapshotItems = entitlementItemRepository
                .findByBillingPlanVersionIdOrderByBucketCodeAsc(billingPlanVersionId)
                .stream()
                .map(this::toEntitlementSnapshotItem)
                .toList();
        subscriptionQuotaGrantService.createGrants(contract.getId(), now, periodEnd, quotaEvent, snapshotItems);
        legacyCreditPolicyProjectionService.projectForUser(userId);
        return contract;
    }

    private EntitlementSnapshotItem toEntitlementSnapshotItem(BillingPlanEntitlementItem item) {
        return new EntitlementSnapshotItem(
                item.getBucketCode(),
                item.getGrantAmountMicros(),
                item.getGrantType(),
                null
        );
    }
}
