package com.josh.interviewj.billing.provider;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record PaymentProviderQueryResult(
        String providerOrderRef,
        String providerEventId,
        String eventType,
        String status,
        BigDecimal amount,
        String currency,
        LocalDateTime occurredAt,
        Map<String, Object> metadata
) {
}
