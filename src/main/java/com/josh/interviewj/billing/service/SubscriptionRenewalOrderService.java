package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanEntitlementItem;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.repository.BillingPlanEntitlementItemRepository;
import com.josh.interviewj.billing.repository.BillingPlanRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionRenewalOrderService {

    private final Clock clock;
    private final BillingProperties billingProperties;
    private final BillingSnapshotCodec billingSnapshotCodec;
    private final InventoryReservationService inventoryReservationService;
    private final BillingPlanRepository billingPlanRepository;
    private final BillingPlanVersionRepository billingPlanVersionRepository;
    private final BillingPlanEntitlementItemRepository entitlementItemRepository;
    private final PaymentOrderRepository paymentOrderRepository;

    @Transactional
    public PaymentOrder ensureNextRenewalOrder(SubscriptionContract contract) {
        if (!contract.isAutoRenew()) {
            return null;
        }
        if (contract.getCurrentPeriodEnd() == null) {
            return null;
        }
        Long targetPlanVersionId = contract.getNextPlanVersionId() != null
                ? contract.getNextPlanVersionId()
                : contract.getBillingPlanVersionId();
        BillingPlanVersion version = billingPlanVersionRepository.findById(targetPlanVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_BILLING_003, "Billing plan version not found"));
        BillingPlan plan = billingPlanRepository.findById(contract.getBillingPlanId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_BILLING_003, "Billing plan not found"));
        List<BillingPlanEntitlementItem> entitlementItems =
                entitlementItemRepository.findByBillingPlanVersionIdOrderByBucketCodeAsc(version.getId());

        LocalDateTime renewalPeriodStart = contract.getCurrentPeriodEnd();
        LocalDateTime renewalPeriodEnd = calculatePeriodEnd(renewalPeriodStart, version.getBillingCycle());
        String idempotencyKey = renewalIdempotencyKey(contract.getId(), renewalPeriodStart, renewalPeriodEnd);
        PaymentOrder existing = paymentOrderRepository.findBySubscriptionContractIdAndRenewalPeriodStartAndRenewalPeriodEndAndOrderType(
                contract.getId(),
                renewalPeriodStart,
                renewalPeriodEnd,
                PaymentOrderType.SUBSCRIPTION_RENEWAL
        ).orElse(null);
        if (existing != null) {
            if (existing.getStatus() != PaymentOrderStatus.SUCCEEDED) {
                Long oldPlanVersionId = existing.getLockedPlanVersionId();

                // Update order fields
                existing.setLockedPlanVersionId(version.getId());
                existing.setAmount(version.getAmount());
                existing.setCurrency(version.getCurrency());
                existing.setPricingSnapshot(billingSnapshotCodec.writePlanPricingSnapshot(plan, version));
                existing.setEntitlementSnapshot(billingSnapshotCodec.writeEntitlementSnapshot(entitlementItems));
                existing.setIdempotencyKey(idempotencyKey);
                existing.setExpiresAt(renewalPeriodStart.plusMinutes(billingProperties.getOrder().getDefaultExpireMinutes()));
                PaymentOrder savedOrder = paymentOrderRepository.save(existing);

                // Rebind reservation if plan version changed or needs reservation
                inventoryReservationService.rebindReservation(
                        savedOrder.getId(),
                        oldPlanVersionId,
                        version.getId(),
                        savedOrder.getCreatedAt(),
                        version
                );

                return savedOrder;
            }
            return existing;
        }

        // Create new renewal order using reserve-first pattern
        LocalDateTime now = nowUtc();

        // Step 1: Reserve inventory atomically BEFORE order creation
        // If reservation fails (insufficient inventory), transaction rolls back immediately
        boolean reservationNeeded = inventoryReservationService.tryReserveInventory(
                version.getId(),
                version,
                now
        );

        // Step 2: Create order after inventory is secured
        PaymentOrder order = PaymentOrder.builder()
                .externalId(UUID.randomUUID())
                .orderNo("po_" + UUID.randomUUID().toString().replace("-", ""))
                .userId(contract.getUserId())
                .orderType(PaymentOrderType.SUBSCRIPTION_RENEWAL)
                .bizRefType("SUBSCRIPTION_CONTRACT")
                .bizRefId(String.valueOf(contract.getId()))
                .subscriptionContractId(contract.getId())
                .lockedPlanVersionId(version.getId())
                .provider(contract.getProvider() == null || contract.getProvider().isBlank() ? "system" : contract.getProvider())
                .amount(version.getAmount())
                .currency(version.getCurrency())
                .status(PaymentOrderStatus.CREATED)
                .idempotencyKey(idempotencyKey)
                .pricingSnapshot(billingSnapshotCodec.writePlanPricingSnapshot(plan, version))
                .entitlementSnapshot(billingSnapshotCodec.writeEntitlementSnapshot(entitlementItems))
                .renewalPeriodStart(renewalPeriodStart)
                .renewalPeriodEnd(renewalPeriodEnd)
                .expiresAt(renewalPeriodStart.plusMinutes(billingProperties.getOrder().getDefaultExpireMinutes()))
                .build();
        PaymentOrder savedOrder = paymentOrderRepository.save(order);

        // Step 3: Create reservation record binding the secured inventory to the order
        inventoryReservationService.createReservationRecord(
                savedOrder.getId(),
                version.getId(),
                reservationNeeded
        );

        savedOrder.setProviderOrderRef(savedOrder.getOrderNo());
        return paymentOrderRepository.save(savedOrder);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public LocalDateTime calculatePeriodEnd(LocalDateTime periodStart, String billingCycle) {
        return switch (billingCycle) {
            case "MONTHLY" -> periodStart.plusMonths(1);
            case "YEARLY" -> periodStart.plusYears(1);
            default -> throw new BusinessException(ErrorCode.ADMIN_BILLING_001, "Unsupported billing cycle: " + billingCycle);
        };
    }

    public String renewalIdempotencyKey(Long contractId, LocalDateTime periodStart, LocalDateTime periodEnd) {
        return contractId + "|" + periodStart + "|" + periodEnd + "|" + PaymentOrderType.SUBSCRIPTION_RENEWAL.name();
    }
}
