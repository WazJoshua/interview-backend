package com.josh.interviewj.billing.provider;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record VerifiedPaymentNotification(
        String provider,
        String providerEventId,
        String providerOrderRef,
        String providerSubscriptionRef,
        String eventType,
        BigDecimal amount,
        String currency,
        LocalDateTime occurredAt,
        String rawPayload,
        Map<String, Object> metadata
) {
}
