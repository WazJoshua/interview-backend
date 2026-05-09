package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.PaymentEvent;
import com.josh.interviewj.billing.model.PaymentEventProcessStatus;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.provider.PaymentProviderAdapter;
import com.josh.interviewj.billing.provider.PaymentProviderRegistry;
import com.josh.interviewj.billing.provider.ProviderWebhookResponse;
import com.josh.interviewj.billing.provider.VerifiedPaymentNotification;
import com.josh.interviewj.billing.repository.PaymentEventRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceTest {

    @Mock
    private PaymentProviderRegistry paymentProviderRegistry;

    @Mock
    private PaymentProviderAdapter paymentProviderAdapter;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private PaymentEventApplicationService paymentEventApplicationService;

    private PaymentWebhookService service;

    @BeforeEach
    void setUp() {
        service = new PaymentWebhookService(
                Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC),
                paymentProviderRegistry,
                paymentOrderRepository,
                paymentEventRepository,
                paymentEventApplicationService,
                new BillingSnapshotCodec(JsonMapper.builder().build())
        );
    }

    @Test
    void handleWebhook_WhenVerified_ReturnsProviderSpecificAck() {
        VerifiedPaymentNotification notification = new VerifiedPaymentNotification(
                "alipay",
                "evt_1",
                "po_123",
                null,
                "PAYMENT_SUCCEEDED",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 10, 0),
                "{\"raw\":true}",
                Map.of()
        );
        PaymentOrder order = PaymentOrder.builder().id(21L).orderNo("po_123").build();
        PaymentEvent savedEvent = PaymentEvent.builder().id(11L).provider("alipay").providerEventId("evt_1").build();

        when(paymentProviderRegistry.requireAdapter("alipay")).thenReturn(paymentProviderAdapter);
        when(paymentProviderAdapter.verifyWebhook("payload", Map.of("x-sign", "1"))).thenReturn(notification);
        when(paymentProviderAdapter.successWebhookResponse()).thenReturn(new ProviderWebhookResponse("success", "text/plain;charset=UTF-8"));
        when(paymentEventRepository.findByProviderAndProviderEventId("alipay", "evt_1")).thenReturn(Optional.empty());
        when(paymentOrderRepository.findByProviderAndProviderOrderRef("alipay", "po_123")).thenReturn(Optional.of(order));
        when(paymentEventRepository.save(any(PaymentEvent.class))).thenReturn(savedEvent);

        ProviderWebhookResponse response = service.handleWebhook("alipay", "payload", Map.of("x-sign", "1"));

        assertThat(response.body()).isEqualTo("success");
        assertThat(response.contentType()).isEqualTo("text/plain;charset=UTF-8");
        verify(paymentEventApplicationService).applyVerifiedEvent(11L, notification);
    }

    @Test
    void ingestVerifiedNotification_WhenEventAlreadyApplied_DoesNotApplyTwice() {
        VerifiedPaymentNotification notification = new VerifiedPaymentNotification(
                "alipay",
                "evt_2",
                "po_123",
                null,
                "PAYMENT_SUCCEEDED",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 10, 0),
                "{\"raw\":true}",
                Map.of()
        );
        PaymentEvent existing = PaymentEvent.builder()
                .id(12L)
                .provider("alipay")
                .providerEventId("evt_2")
                .processStatus(PaymentEventProcessStatus.APPLIED)
                .build();

        when(paymentEventRepository.findByProviderAndProviderEventId("alipay", "evt_2")).thenReturn(Optional.of(existing));

        service.ingestVerifiedNotification(notification);

        verify(paymentEventApplicationService, never()).applyVerifiedEvent(any(), any());
        verify(paymentEventRepository, never()).save(any(PaymentEvent.class));
    }
}
