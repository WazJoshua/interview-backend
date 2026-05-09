package com.josh.interviewj.billing.provider;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record PaymentRefundResult(
        String providerRefundRef,
        String status,
        BigDecimal amount,
        String currency,
        LocalDateTime completedAt,
        Map<String, Object> metadata
) {
}
