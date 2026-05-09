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
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    private final Clock clock;
    private final PaymentProviderRegistry paymentProviderRegistry;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final PaymentEventApplicationService paymentEventApplicationService;
    private final BillingSnapshotCodec billingSnapshotCodec;

    @Transactional
    public ProviderWebhookResponse handleWebhook(String provider, String payload, Map<String, String> headers) {
        PaymentProviderAdapter adapter = paymentProviderRegistry.requireAdapter(provider);
        VerifiedPaymentNotification notification;
        try {
            notification = adapter.verifyWebhook(payload, headers);
        } catch (BusinessException exception) {
            persistInvalidWebhook(provider, payload, exception.getMessage());
            throw exception;
        }
        ingestVerifiedNotification(notification);
        return adapter.successWebhookResponse();
    }

    @Transactional
    public void ingestVerifiedNotification(VerifiedPaymentNotification notification) {
        PaymentEvent existing = paymentEventRepository.findByProviderAndProviderEventId(notification.provider(), notification.providerEventId())
                .orElse(null);
        if (existing != null) {
            if (existing.getProcessStatus() == PaymentEventProcessStatus.APPLIED
                    || existing.getProcessStatus() == PaymentEventProcessStatus.APPLYING) {
                return;
            }
            paymentEventApplicationService.applyVerifiedEvent(existing.getId(), notification);
            return;
        }

        PaymentOrder order = paymentOrderRepository.findByProviderAndProviderOrderRef(notification.provider(), notification.providerOrderRef())
                .orElseGet(() -> paymentOrderRepository.findByOrderNo(notification.providerOrderRef()).orElse(null));
        PaymentEvent event = paymentEventRepository.save(PaymentEvent.builder()
                .externalId(UUID.randomUUID())
                .paymentOrderId(order == null ? null : order.getId())
                .provider(notification.provider())
                .providerEventId(notification.providerEventId())
                .providerOrderRef(notification.providerOrderRef())
                .eventType(notification.eventType())
                .signatureValid(true)
                .rawPayload(serializeRawPayload(notification.rawPayload()))
                .occurredAt(notification.occurredAt())
                .processStatus(PaymentEventProcessStatus.RECEIVED)
                .applyAttemptCount(0)
                .build());
        paymentEventApplicationService.applyVerifiedEvent(event.getId(), notification);
    }

    private void persistInvalidWebhook(String provider, String payload, String errorMessage) {
        String providerEventId = "invalid:" + sha256(payload);
        if (paymentEventRepository.findByProviderAndProviderEventId(provider, providerEventId).isPresent()) {
            return;
        }
        paymentEventRepository.save(PaymentEvent.builder()
                .externalId(UUID.randomUUID())
                .provider(provider)
                .providerEventId(providerEventId)
                .eventType("INVALID_SIGNATURE")
                .signatureValid(false)
                .rawPayload(serializeRawPayload(payload))
                .occurredAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .processStatus(PaymentEventProcessStatus.FAILED_TERMINAL)
                .applyAttemptCount(0)
                .lastErrorCode(ErrorCode.PAYMENT_001)
                .lastErrorMessage(errorMessage)
                .build());
    }

    private String serializeRawPayload(String payload) {
        return billingSnapshotCodec.write(Map.of("rawPayload", payload == null ? "" : payload));
    }

    private String sha256(String payload) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(messageDigest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            return UUID.randomUUID().toString();
        }
    }
}
