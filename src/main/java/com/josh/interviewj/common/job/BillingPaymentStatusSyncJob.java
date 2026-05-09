package com.josh.interviewj.common.job;

import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.provider.PaymentProviderAdapter;
import com.josh.interviewj.billing.provider.PaymentProviderQueryRequest;
import com.josh.interviewj.billing.provider.PaymentProviderQueryResult;
import com.josh.interviewj.billing.provider.PaymentProviderRegistry;
import com.josh.interviewj.billing.provider.VerifiedPaymentNotification;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.billing.service.PaymentWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "app.billing.payment-status-sync.enabled", havingValue = "true", matchIfMissing = true)
public class BillingPaymentStatusSyncJob {

    private final Clock clock;
    private final BillingProperties billingProperties;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentProviderRegistry paymentProviderRegistry;
    private final PaymentWebhookService paymentWebhookService;

    @Scheduled(fixedDelayString = "${app.billing.payment-status-sync.poll-interval:300000}")
    public void syncPendingOrders() {
        if (billingProperties == null || !billingProperties.getPaymentStatusSync().isEnabled()) {
            return;
        }

        LocalDateTime updatedBefore = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minus(billingProperties.getPaymentStatusSync().getMaxPendingAge());

        for (PaymentOrder order : paymentOrderRepository.findOrdersForPaymentStatusSync(
                updatedBefore,
                billingProperties.getPaymentStatusSync().getBatchSize()
        )) {
            try {
                PaymentProviderAdapter adapter = paymentProviderRegistry.requireAdapter(order.getProvider());
                PaymentProviderQueryResult result = adapter.queryPayment(new PaymentProviderQueryRequest(
                        order.getOrderNo(),
                        order.getProviderOrderRef()
                ));
                handleQueryResult(order, result);
            } catch (RuntimeException exception) {
                log.warn("billing_payment_status_sync_failed orderNo={}, message={}", order.getOrderNo(), exception.getMessage());
            }
        }
    }

    private void handleQueryResult(PaymentOrder order, PaymentProviderQueryResult result) {
        if ("SUCCEEDED".equalsIgnoreCase(result.status())) {
            paymentWebhookService.ingestVerifiedNotification(new VerifiedPaymentNotification(
                    order.getProvider(),
                    result.providerEventId(),
                    result.providerOrderRef(),
                    null,
                    result.eventType(),
                    result.amount(),
                    result.currency(),
                    result.occurredAt(),
                    "{}",
                    result.metadata() == null ? Map.of() : result.metadata()
            ));
            return;
        }
        if ("CLOSED".equalsIgnoreCase(result.status()) || "FAILED".equalsIgnoreCase(result.status())) {
            order.setStatus(PaymentOrderStatus.FAILED);
            paymentOrderRepository.save(order);
        }
    }
}
