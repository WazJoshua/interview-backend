package com.josh.interviewj.billing.provider;

public record PaymentProviderQueryRequest(
        String orderNo,
        String providerOrderRef
) {
}
