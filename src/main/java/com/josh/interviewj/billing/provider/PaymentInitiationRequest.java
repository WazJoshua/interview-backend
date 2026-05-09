package com.josh.interviewj.billing.provider;

import java.math.BigDecimal;

public record PaymentInitiationRequest(
        String orderNo,
        BigDecimal amount,
        String currency,
        String terminal,
        String subject,
        String returnUrl
) {
}
