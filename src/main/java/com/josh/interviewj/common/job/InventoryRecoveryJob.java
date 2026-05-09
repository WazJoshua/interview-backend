package com.josh.interviewj.common.job;

import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.service.InventoryRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled job for recovering orphan inventory reservations.
 * <p>
 * Orphan reservations are RESERVED records that have exceeded the configurable TTL,
 * typically due to system failures, crashes, or incomplete order lifecycle handling.
 * <p>
 * The job runs at startup and then periodically to ensure inventory consistency.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "app.billing.inventory-recovery.enabled", havingValue = "true", matchIfMissing = true)
public class InventoryRecoveryJob {

    private final Clock clock;
    private final BillingProperties billingProperties;
    private final InventoryRecoveryService inventoryRecoveryService;

    /**
     * Run recovery on application startup to clean up any orphan reservations
     * from previous crashes or incomplete lifecycle handling.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!billingProperties.getInventoryRecovery().isEnabled()) {
            return;
        }

        log.info("Running inventory recovery on startup...");

        LocalDateTime cutoffTimestamp = calculateCutoffTimestamp();
        InventoryRecoveryService.RecoveryResult result = inventoryRecoveryService.recoverOrphanReservations(
                cutoffTimestamp,
                billingProperties.getInventoryRecovery().getBatchSize()
        );

        if (result.hasActivity()) {
            log.info("Startup inventory recovery completed: scanned={}, recovered={}, skipped={}, failures={}",
                    result.scannedCount(), result.recoveredCount(), result.skippedCount(), result.failureCount());
        } else {
            log.info("Startup inventory recovery completed: no orphan reservations found");
        }
    }

    /**
     * Periodically scan and recover orphan reservations.
     */
    @Scheduled(fixedDelayString = "#{@billingProperties.inventoryRecovery.pollInterval.toMillis()}")
    public void recoverPeriodically() {
        if (!billingProperties.getInventoryRecovery().isEnabled()) {
            return;
        }

        LocalDateTime cutoffTimestamp = calculateCutoffTimestamp();
        InventoryRecoveryService.RecoveryResult result = inventoryRecoveryService.recoverOrphanReservations(
                cutoffTimestamp,
                billingProperties.getInventoryRecovery().getBatchSize()
        );

        if (result.hasActivity()) {
            log.info("Periodic inventory recovery completed: scanned={}, recovered={}, skipped={}, failures={}",
                    result.scannedCount(), result.recoveredCount(), result.skippedCount(), result.failureCount());
        }
    }

    /**
     * Calculate the cutoff timestamp based on TTL configuration.
     * Reservations created before this time are considered orphans.
     */
    private LocalDateTime calculateCutoffTimestamp() {
        long ttlHours = billingProperties.getInventoryRecovery().getOrphanReservationTtlHours();
        return nowUtc().minus(ttlHours, ChronoUnit.HOURS);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}