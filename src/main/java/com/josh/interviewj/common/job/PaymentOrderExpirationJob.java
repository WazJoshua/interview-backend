package com.josh.interviewj.common.job;

import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.service.PaymentOrderLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Scheduled job for processing expired payment orders.
 * <p>
 * This job handles normal order expiration by:
 * 1. Finding orders with CREATED status that have exceeded their expiresAt time
 * 2. Marking them as EXPIRED
 * 3. Releasing any active inventory reservations
 * <p>
 * This is the primary expiration mechanism, while InventoryRecoveryJob serves as a
 * safety net for orphan reservations that may occur due to system failures.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "app.billing.order-expiration.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentOrderExpirationJob {

    private final Clock clock;
    private final BillingProperties billingProperties;
    private final PaymentOrderLifecycleService paymentOrderLifecycleService;

    /**
     * Periodically scan and process expired payment orders.
     */
    @Scheduled(fixedDelayString = "#{@billingProperties.orderExpiration.pollInterval.toMillis()}")
    public void processExpiredOrders() {
        if (!billingProperties.getOrderExpiration().isEnabled()) {
            return;
        }

        LocalDateTime cutoffTimestamp = nowUtc();
        int batchSize = billingProperties.getOrderExpiration().getBatchSize();

        int processedCount = paymentOrderLifecycleService.processExpiredOrders(cutoffTimestamp, batchSize);

        if (processedCount > 0) {
            log.info("Order expiration job completed: processed {} expired orders", processedCount);
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}