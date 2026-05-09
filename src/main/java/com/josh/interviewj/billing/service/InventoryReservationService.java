package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.BillingInventoryReservation;
import com.josh.interviewj.billing.model.BillingPlanInventory;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.InventoryConfirmationResult;
import com.josh.interviewj.billing.model.InventoryReservationStatus;
import com.josh.interviewj.billing.repository.BillingInventoryReservationRepository;
import com.josh.interviewj.billing.repository.BillingPlanInventoryRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Service for inventory reservation operations.
 * Implements the "reserve-first" model for subscription orders.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryReservationService {

    private final Clock clock;
    private final BillingPlanInventoryRepository inventoryRepository;
    private final BillingInventoryReservationRepository reservationRepository;
    private final BillingEventService billingEventService;
    private final BillingSnapshotCodec billingSnapshotCodec;

    /**
     * Check if inventory control is required for a plan version.
     * Uses inventory_control_enabled_at as the authoritative cutoff.
     *
     * @param version the billing plan version
     * @param orderCreatedAt the order creation timestamp
     * @return true if reservation is required, false for legacy compatibility
     */
    public boolean isReservationRequired(BillingPlanVersion version, LocalDateTime orderCreatedAt) {
        if (version.getInventoryControlEnabledAt() == null) {
            // Inventory control never enabled for this version
            return false;
        }
        // Reservation required if order created after the cutoff
        return !orderCreatedAt.isBefore(version.getInventoryControlEnabledAt());
    }

    /**
     * Step 1 of reserve-first pattern: atomically reserve inventory without creating reservation record.
     * This secures inventory before order creation.
     * Throws USER_BILLING_004 if insufficient inventory.
     * Throws USER_BILLING_005 if inventory record missing when control is enabled.
     *
     * @param planVersionId the billing plan version ID
     * @param version the billing plan version (for legacy check)
     * @param orderCreatedAt the order creation timestamp (for legacy check)
     * @return true if reservation was made, false if legacy (no inventory control needed)
     */
    public boolean tryReserveInventory(
            Long planVersionId,
            BillingPlanVersion version,
            LocalDateTime orderCreatedAt
    ) {
        // Check legacy compatibility
        if (!isReservationRequired(version, orderCreatedAt)) {
            log.debug("Legacy order without reservation: planVersionId={}, cutoff={}",
                    planVersionId, version.getInventoryControlEnabledAt());
            return false;
        }

        // Verify inventory record exists
        BillingPlanInventory inventory = inventoryRepository.findByBillingPlanVersionId(planVersionId)
                .orElseThrow(() -> {
                    log.warn("Inventory record missing for enabled control: planVersionId={}", planVersionId);
                    return new BusinessException(
                            ErrorCode.USER_BILLING_005,
                            "Inventory configuration missing for this plan version"
                    );
                });

        // Atomically reserve one unit
        int updated = inventoryRepository.reserveOneIfAvailable(planVersionId);
        if (updated == 0) {
            log.warn("Insufficient inventory: planVersionId={}, available={}",
                    planVersionId, inventory.getAvailableCount());
            throw new BusinessException(
                    ErrorCode.USER_BILLING_004,
                    "Insufficient inventory for this plan version"
            );
        }

        log.info("Inventory reserved atomically (step 1): planVersionId={}", planVersionId);
        return true;
    }

    /**
     * Step 2 of reserve-first pattern: create reservation record binding inventory to order.
     * This should be called after order creation to link the reserved inventory to the orderId.
     * Must be called within the same transaction as tryReserveInventory().
     *
     * @param paymentOrderId the payment order ID (obtained after order creation)
     * @param planVersionId the billing plan version ID
     * @param reservationNeeded whether reservation is needed (from tryReserveInventory result)
     * @return the created reservation record, or null if legacy (no inventory control)
     */
    public BillingInventoryReservation createReservationRecord(
            Long paymentOrderId,
            Long planVersionId,
            boolean reservationNeeded
    ) {
        if (!reservationNeeded) {
            log.debug("Skipping reservation record creation for legacy order: paymentOrderId={}", paymentOrderId);
            return null;
        }

        // Create reservation record
        BillingInventoryReservation reservation = BillingInventoryReservation.builder()
                .externalId(UUID.randomUUID())
                .paymentOrderId(paymentOrderId)
                .billingPlanVersionId(planVersionId)
                .status(InventoryReservationStatus.RESERVED)
                .build();

        BillingInventoryReservation saved = reservationRepository.save(reservation);
        log.info("Reservation record created (step 2): reservationId={}, paymentOrderId={}, planVersionId={}",
                saved.getId(), paymentOrderId, planVersionId);

        return saved;
    }

    /**
     * Reserve inventory for a payment order (legacy method - prefer tryReserveInventory + createReservationRecord).
     * Atomically decrements available count and creates reservation record.
     * Throws USER_BILLING_004 if insufficient inventory.
     * Throws USER_BILLING_005 if inventory record missing when control is enabled.
     *
     * @param paymentOrderId the payment order ID
     * @param planVersionId the billing plan version ID
     * @param orderCreatedAt the order creation timestamp
     * @param version the billing plan version (for legacy check)
     * @return the created reservation record
     */
    @Transactional
    public BillingInventoryReservation reserveForOrder(
            Long paymentOrderId,
            Long planVersionId,
            LocalDateTime orderCreatedAt,
            BillingPlanVersion version
    ) {
        // Check legacy compatibility
        if (!isReservationRequired(version, orderCreatedAt)) {
            log.info("Legacy order without reservation: paymentOrderId={}, planVersionId={}, cutoff={}",
                    paymentOrderId, planVersionId, version.getInventoryControlEnabledAt());
            return null;
        }

        // Verify inventory record exists
        BillingPlanInventory inventory = inventoryRepository.findByBillingPlanVersionId(planVersionId)
                .orElseThrow(() -> {
                    log.warn("Inventory record missing for enabled control: planVersionId={}", planVersionId);
                    return new BusinessException(
                            ErrorCode.USER_BILLING_005,
                            "Inventory configuration missing for this plan version"
                    );
                });

        // Atomically reserve one unit
        int updated = inventoryRepository.reserveOneIfAvailable(planVersionId);
        if (updated == 0) {
            log.warn("Insufficient inventory: planVersionId={}, available={}",
                    planVersionId, inventory.getAvailableCount());
            throw new BusinessException(
                    ErrorCode.USER_BILLING_004,
                    "Insufficient inventory for this plan version"
            );
        }

        // Create reservation record
        BillingInventoryReservation reservation = BillingInventoryReservation.builder()
                .externalId(UUID.randomUUID())
                .paymentOrderId(paymentOrderId)
                .billingPlanVersionId(planVersionId)
                .status(InventoryReservationStatus.RESERVED)
                .build();

        BillingInventoryReservation saved = reservationRepository.save(reservation);
        log.info("Reservation created: reservationId={}, paymentOrderId={}, planVersionId={}",
                saved.getId(), paymentOrderId, planVersionId);

        return saved;
    }

    /**
     * Release an existing reservation and create a new one for a different plan version.
     * Used when renewal order needs to update the locked plan version.
     * Must be called within the same transaction as the order update.
     *
     * @param paymentOrderId the payment order ID
     * @param oldPlanVersionId the previous plan version ID to release
     * @param newPlanVersionId the new plan version ID to reserve
     * @param orderCreatedAt the order creation timestamp
     * @param newVersion the new billing plan version (for legacy check)
     * @return the new reservation record (or null if legacy)
     */
    @Transactional
    public BillingInventoryReservation rebindReservation(
            Long paymentOrderId,
            Long oldPlanVersionId,
            Long newPlanVersionId,
            java.time.LocalDateTime orderCreatedAt,
            BillingPlanVersion newVersion
    ) {
        // Check legacy compatibility for new version
        if (!isReservationRequired(newVersion, orderCreatedAt)) {
            // Still need to release old reservation if it exists
            releaseForOrder(paymentOrderId, oldPlanVersionId);
            return null;
        }

        // If plan version unchanged, check if active reservation exists
        if (oldPlanVersionId.equals(newPlanVersionId)) {
            boolean hasActive = reservationRepository.existsActiveReservationByPaymentOrderId(paymentOrderId);
            if (hasActive) {
                log.info("Reservation already exists for unchanged version: paymentOrderId={}, planVersionId={}",
                        paymentOrderId, newPlanVersionId);
                return reservationRepository.findByPaymentOrderIdAndStatus(
                        paymentOrderId, InventoryReservationStatus.RESERVED
                ).orElse(null);
            }
            // No active reservation, need to create one
            return reserveForOrder(paymentOrderId, newPlanVersionId, orderCreatedAt, newVersion);
        }

        // Release old reservation first (if exists)
        releaseForOrder(paymentOrderId, oldPlanVersionId);

        // Reserve for new version
        return reserveForOrder(paymentOrderId, newPlanVersionId, orderCreatedAt, newVersion);
    }

    /**
     * Release reservation for an order.
     * Atomically increments available count and updates reservation status.
     * Idempotent: safe to call multiple times.
     *
     * @param paymentOrderId the payment order ID
     * @param planVersionId the billing plan version ID
     */
    @Transactional
    public void releaseForOrder(Long paymentOrderId, Long planVersionId) {
        // Update reservation status (RESERVED -> RELEASED)
        int reservationUpdated = reservationRepository.releaseIfReserved(paymentOrderId);
        if (reservationUpdated == 0) {
            // Already released or confirmed, nothing to do
            log.debug("Reservation already in terminal state: paymentOrderId={}", paymentOrderId);
            return;
        }

        // Release inventory count
        int inventoryUpdated = inventoryRepository.releaseOneReservation(planVersionId);
        if (inventoryUpdated == 0) {
            log.warn("Inventory release failed but reservation released: paymentOrderId={}, planVersionId={}",
                    paymentOrderId, planVersionId);
            // This indicates a data inconsistency; will need reconciliation
        }

        log.info("Reservation released: paymentOrderId={}, planVersionId={}", paymentOrderId, planVersionId);
    }

    /**
     * Confirm reservation after successful payment.
     * Atomically moves from reserved to confirmed count.
     * Idempotent: safe to call multiple times.
     *
     * Handles legacy orders (created before inventory_control_enabled_at) and
     * post-cutover orders (created after) with appropriate reconciliation events.
     *
     * IMPORTANT: This method should be called BEFORE any fulfillment side effects
     * (billing events, subscription contracts, quota grants) to avoid partial commits.
     *
     * @param paymentOrderId the payment order ID
     * @param planVersionId the billing plan version ID
     * @param version the billing plan version (for legacy check)
     * @param orderCreatedAt the order creation timestamp
     * @param userId the user ID (for reconciliation event)
     * @param orderNo the order number (for reconciliation event metadata)
     * @return CONFIRMED if reservation confirmed successfully;
     *         LEGACY_ALLOWED if legacy order without reservation (fulfillment may proceed);
     *         REQUIRES_RECONCILIATION if post-cutover order without reservation (fulfillment must NOT proceed)
     */
    @Transactional
    public InventoryConfirmationResult confirmForOrder(
            Long paymentOrderId,
            Long planVersionId,
            BillingPlanVersion version,
            LocalDateTime orderCreatedAt,
            Long userId,
            String orderNo
    ) {
        // Update reservation status (RESERVED -> CONFIRMED)
        int reservationUpdated = reservationRepository.confirmIfReserved(paymentOrderId);
        if (reservationUpdated == 0) {
            // No RESERVED record found - check if already CONFIRMED (idempotent duplicate webhook)
            return handleAlreadyTerminalReservation(paymentOrderId, planVersionId, version, orderCreatedAt, userId, orderNo);
        }

        // Confirm inventory (move from reserved to confirmed count)
        int inventoryUpdated = inventoryRepository.confirmOneReservation(planVersionId);
        if (inventoryUpdated == 0) {
            log.warn("Inventory confirm failed but reservation confirmed: paymentOrderId={}, planVersionId={}",
                    paymentOrderId, planVersionId);
            // This indicates a data inconsistency; will need reconciliation
        }

        log.info("Reservation confirmed: paymentOrderId={}, planVersionId={}", paymentOrderId, planVersionId);
        return InventoryConfirmationResult.CONFIRMED;
    }

    /**
     * Handle the case when confirmIfReserved() returns 0 (no RESERVED record was updated).
     * This could mean:
     * 1. Already CONFIRMED (duplicate success webhook - should be idempotent no-op)
     * 2. Already RELEASED (order was canceled/expired but payment succeeded - needs reconciliation)
     * 3. No reservation record at all (legacy or missing reservation - needs special handling)
     *
     * @param paymentOrderId the payment order ID
     * @param planVersionId the billing plan version ID
     * @param version the billing plan version (for legacy check)
     * @param orderCreatedAt the order creation timestamp
     * @param userId the user ID
     * @param orderNo the order number
     * @return CONFIRMED if already confirmed (idempotent), LEGACY_ALLOWED, or REQUIRES_RECONCILIATION
     */
    private InventoryConfirmationResult handleAlreadyTerminalReservation(
            Long paymentOrderId,
            Long planVersionId,
            BillingPlanVersion version,
            LocalDateTime orderCreatedAt,
            Long userId,
            String orderNo
    ) {
        // Check if reservation exists in any status
        BillingInventoryReservation existingReservation = reservationRepository.findByPaymentOrderId(paymentOrderId).orElse(null);

        if (existingReservation != null) {
            if (existingReservation.getStatus() == InventoryReservationStatus.CONFIRMED) {
                // Already confirmed - this is a duplicate success webhook, idempotent no-op
                log.info("Reservation already confirmed (duplicate webhook): paymentOrderId={}, planVersionId={}",
                        paymentOrderId, planVersionId);
                return InventoryConfirmationResult.CONFIRMED;
            }

            if (existingReservation.getStatus() == InventoryReservationStatus.RELEASED) {
                // Reservation was released (order canceled/expired) but payment succeeded
                // This is an anomaly requiring reconciliation
                log.warn("Reservation already released but payment succeeded: paymentOrderId={}, planVersionId={}",
                        paymentOrderId, planVersionId);

                billingEventService.createOrGet(
                        userId,
                        BillingEventType.POST_CUTOVER_ORDER_WITHOUT_RESERVATION,
                        "INVENTORY_RECONCILIATION",
                        String.valueOf(paymentOrderId),
                        "reservation-released-but-payment-succeeded|" + paymentOrderId,
                        0L,
                        null,
                        nowUtc(),
                        Map.of(
                                "paymentOrderId", paymentOrderId,
                                "planVersionId", planVersionId,
                                "orderNo", orderNo,
                                "reservationStatus", "RELEASED",
                                "issue", "Reservation was released before payment success"
                        )
                );

                return InventoryConfirmationResult.REQUIRES_RECONCILIATION;
            }
        }

        // No reservation record at all - handle as legacy or post-cutover missing reservation
        return handleMissingReservation(paymentOrderId, planVersionId, version, orderCreatedAt, userId, orderNo);
    }

    /**
     * Handle the case when no active reservation is found for an order during confirmation.
     *
     * Legacy order (created before inventory_control_enabled_at):
     * - Record LEGACY_ORDER_WITHOUT_RESERVATION reconciliation event
     * - Return LEGACY_ALLOWED to signal fulfillment may proceed
     *
     * Post-cutover order (created after inventory_control_enabled_at):
     * - Record POST_CUTOVER_ORDER_WITHOUT_RESERVATION reconciliation event
     * - Return REQUIRES_RECONCILIATION to signal fulfillment must NOT proceed
     * - Caller should mark order for reconciliation and NOT perform fulfillment
     *
     * @param paymentOrderId the payment order ID
     * @param planVersionId the billing plan version ID
     * @param version the billing plan version (for legacy check)
     * @param orderCreatedAt the order creation timestamp
     * @param userId the user ID
     * @param orderNo the order number
     * @return LEGACY_ALLOWED or REQUIRES_RECONCILIATION
     */
    private InventoryConfirmationResult handleMissingReservation(
            Long paymentOrderId,
            Long planVersionId,
            BillingPlanVersion version,
            LocalDateTime orderCreatedAt,
            Long userId,
            String orderNo
    ) {
        boolean isLegacyOrder = !isReservationRequired(version, orderCreatedAt);

        if (isLegacyOrder) {
            // Legacy order: allow fulfillment, record reconciliation event
            log.info("Legacy order without reservation: paymentOrderId={}, planVersionId={}, orderCreatedAt={}, cutoff={}",
                    paymentOrderId, planVersionId, orderCreatedAt, version.getInventoryControlEnabledAt());

            billingEventService.createOrGet(
                    userId,
                    BillingEventType.LEGACY_ORDER_WITHOUT_RESERVATION,
                    "INVENTORY_RECONCILIATION",
                    String.valueOf(paymentOrderId),
                    "legacy-reservation-missing|" + paymentOrderId,
                    0L,
                    null,
                    nowUtc(),
                    Map.of(
                            "paymentOrderId", paymentOrderId,
                            "planVersionId", planVersionId,
                            "orderNo", orderNo,
                            "orderCreatedAt", orderCreatedAt.toString(),
                            "inventoryControlEnabledAt", version.getInventoryControlEnabledAt() != null
                                    ? version.getInventoryControlEnabledAt().toString() : "null"
                    )
            );
            // Allow fulfillment to proceed without inventory confirm/release
            return InventoryConfirmationResult.LEGACY_ALLOWED;
        }

        // Post-cutover order: block fulfillment, require manual reconciliation
        log.warn("Post-cutover order without reservation requires manual reconciliation: paymentOrderId={}, planVersionId={}, orderCreatedAt={}, cutoff={}",
                paymentOrderId, planVersionId, orderCreatedAt, version.getInventoryControlEnabledAt());

        billingEventService.createOrGet(
                userId,
                BillingEventType.POST_CUTOVER_ORDER_WITHOUT_RESERVATION,
                "INVENTORY_RECONCILIATION",
                String.valueOf(paymentOrderId),
                "post-cutover-reservation-missing|" + paymentOrderId,
                0L,
                null,
                nowUtc(),
                Map.of(
                        "paymentOrderId", paymentOrderId,
                        "planVersionId", planVersionId,
                        "orderNo", orderNo,
                        "orderCreatedAt", orderCreatedAt.toString(),
                        "inventoryControlEnabledAt", version.getInventoryControlEnabledAt().toString()
                )
        );

        // Return REQUIRES_RECONCILIATION instead of throwing exception
        // Caller should mark order for reconciliation and NOT proceed with fulfillment
        return InventoryConfirmationResult.REQUIRES_RECONCILIATION;
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}