package com.josh.interviewj.billing.provider;

import java.math.BigDecimal;

public record PaymentRefundRequest(
        String orderNo,
        String providerOrderRef,
        String refundRequestId,
        BigDecimal amount,
        String currency,
        String reason
) {
}
