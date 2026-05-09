package com.josh.interviewj.billing.provider;

public record PaymentInitiationResult(
        String providerOrderRef,
        String redirectUrl
) {
}
