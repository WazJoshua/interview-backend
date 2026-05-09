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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingPaymentStatusSyncJobTest {

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private PaymentProviderRegistry paymentProviderRegistry;

    @Mock
    private PaymentProviderAdapter paymentProviderAdapter;

    @Mock
    private PaymentWebhookService paymentWebhookService;

    private BillingProperties billingProperties;
    private BillingPaymentStatusSyncJob job;

    @BeforeEach
    void setUp() {
        billingProperties = new BillingProperties();
        billingProperties.getPaymentStatusSync().setEnabled(true);
        billingProperties.getPaymentStatusSync().setBatchSize(20);
        billingProperties.getPaymentStatusSync().setMaxPendingAge(java.time.Duration.ofMinutes(30));
        job = new BillingPaymentStatusSyncJob(
                Clock.fixed(Instant.parse("2026-04-02T12:00:00Z"), ZoneOffset.UTC),
                billingProperties,
                paymentOrderRepository,
                paymentProviderRegistry,
                paymentWebhookService
        );
    }

    @Test
    void syncPendingOrders_WhenProviderReportsSuccess_DelegatesThroughPaymentEventPath() {
        PaymentOrder order = pendingOrder("po_123", PaymentOrderStatus.PENDING_PROVIDER);
        when(paymentOrderRepository.findOrdersForPaymentStatusSync(LocalDateTime.of(2026, 4, 2, 11, 30), 20))
                .thenReturn(List.of(order));
        when(paymentProviderRegistry.requireAdapter("alipay")).thenReturn(paymentProviderAdapter);
        when(paymentProviderAdapter.queryPayment(new PaymentProviderQueryRequest("po_123", "po_123")))
                .thenReturn(new PaymentProviderQueryResult(
                        "po_123",
                        "trade_123",
                        "PAYMENT_SUCCEEDED",
                        "SUCCEEDED",
                        new BigDecimal("29.900000"),
                        "USD",
                        LocalDateTime.of(2026, 4, 2, 11, 0),
                        Map.of("tradeStatus", "TRADE_SUCCESS")
                ));

        job.syncPendingOrders();

        verify(paymentWebhookService).ingestVerifiedNotification(new VerifiedPaymentNotification(
                "alipay",
                "trade_123",
                "po_123",
                null,
                "PAYMENT_SUCCEEDED",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 11, 0),
                "{}",
                Map.of("tradeStatus", "TRADE_SUCCESS")
        ));
    }

    @Test
    void syncPendingOrders_WhenProviderReportsClosed_MarksOrderFailed() {
        PaymentOrder order = pendingOrder("po_456", PaymentOrderStatus.AWAITING_CONFIRMATION);
        when(paymentOrderRepository.findOrdersForPaymentStatusSync(LocalDateTime.of(2026, 4, 2, 11, 30), 20))
                .thenReturn(List.of(order));
        when(paymentProviderRegistry.requireAdapter("alipay")).thenReturn(paymentProviderAdapter);
        when(paymentProviderAdapter.queryPayment(new PaymentProviderQueryRequest("po_456", "po_456")))
                .thenReturn(new PaymentProviderQueryResult(
                        "po_456",
                        "trade_456",
                        "PAYMENT_CLOSED",
                        "CLOSED",
                        new BigDecimal("29.900000"),
                        "USD",
                        LocalDateTime.of(2026, 4, 2, 11, 5),
                        Map.of("tradeStatus", "TRADE_CLOSED")
                ));
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        job.syncPendingOrders();

        verify(paymentOrderRepository).save(order);
        verify(paymentWebhookService, never()).ingestVerifiedNotification(any());
    }

    private PaymentOrder pendingOrder(String orderNo, PaymentOrderStatus status) {
        return PaymentOrder.builder()
                .id(orderNo.equals("po_123") ? 11L : 12L)
                .externalId(UUID.randomUUID())
                .orderNo(orderNo)
                .userId(101L)
                .provider("alipay")
                .providerOrderRef(orderNo)
                .status(status)
                .payableActivatedAt(LocalDateTime.of(2026, 4, 2, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 4, 2, 10, 0))
                .build();
    }
}
