package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingInventoryReservation;
import com.josh.interviewj.billing.model.InventoryReservationStatus;
import com.josh.interviewj.billing.repository.BillingInventoryReservationRepository;
import com.josh.interviewj.billing.repository.BillingPlanInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for recovering orphan inventory reservations.
 * <p>
 * Orphan reservations are RESERVED records that have exceeded the configurable TTL,
 * typically due to system failures, crashes, or incomplete order lifecycle handling.
 * <p>
 * Recovery is idempotent - releasing the same reservation multiple times has no additional effect.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryRecoveryService {

    private final BillingInventoryReservationRepository reservationRepository;
    private final BillingPlanInventoryRepository inventoryRepository;

    /**
     * Scan and recover orphan reservations created before the cutoff timestamp.
     *
     * @param cutoffTimestamp reservations created before this time are considered orphans
     * @param batchSize maximum number of reservations to process in one batch
     * @return recovery result with counts
     */
    @Transactional
    public RecoveryResult recoverOrphanReservations(LocalDateTime cutoffTimestamp, int batchSize) {
        List<BillingInventoryReservation> orphans = reservationRepository.findOrphanReservedReservations(
                cutoffTimestamp,
                batchSize
        );

        int scannedCount = orphans.size();
        int recoveredCount = 0;
        int skippedCount = 0;
        int failureCount = 0;

        for (BillingInventoryReservation reservation : orphans) {
            try {
                boolean recovered = recoverSingleReservation(reservation);
                if (recovered) {
                    recoveredCount++;
                    log.info("Recovered orphan reservation: reservationId={}, paymentOrderId={}, planVersionId={}, createdAt={}",
                            reservation.getId(),
                            reservation.getPaymentOrderId(),
                            reservation.getBillingPlanVersionId(),
                            reservation.getCreatedAt());
                } else {
                    skippedCount++;
                    log.debug("Skipped reservation (already released): reservationId={}", reservation.getId());
                }
            } catch (Exception e) {
                failureCount++;
                log.warn("Failed to recover reservation: reservationId={}, error={}",
                        reservation.getId(), e.getMessage());
            }
        }

        if (scannedCount > 0 || recoveredCount > 0 || failureCount > 0) {
            log.info("Inventory recovery batch completed: scanned={}, recovered={}, skipped={}, failures={}",
                    scannedCount, recoveredCount, skippedCount, failureCount);
        }

        return new RecoveryResult(scannedCount, recoveredCount, skippedCount, failureCount);
    }

    /**
     * Recover a single orphan reservation.
     * Idempotent - if already released or confirmed, no action is taken.
     *
     * @param reservation the reservation to recover
     * @return true if successfully recovered, false if already in terminal state
     */
    private boolean recoverSingleReservation(BillingInventoryReservation reservation) {
        // Idempotent release: only succeeds if still RESERVED
        int rowsUpdated = reservationRepository.releaseByIdIfReserved(reservation.getId());

        if (rowsUpdated == 0) {
            // Already in terminal state (CONFIRMED or RELEASED)
            return false;
        }

        // Decrement reserved count in inventory master
        int inventoryRowsUpdated = inventoryRepository.releaseOneReservation(
                reservation.getBillingPlanVersionId()
        );

        if (inventoryRowsUpdated == 0) {
            log.warn("Reservation released but inventory count not decremented: planVersionId={}, reservationId={}",
                    reservation.getBillingPlanVersionId(), reservation.getId());
        }

        return true;
    }

    /**
     * Result of inventory recovery batch processing.
     */
    public record RecoveryResult(
            int scannedCount,
            int recoveredCount,
            int skippedCount,
            int failureCount
    ) {
        /**
         * Check if any reservations were processed.
         */
        public boolean hasActivity() {
            return scannedCount > 0 || recoveredCount > 0 || skippedCount > 0 || failureCount > 0;
        }
    }
}