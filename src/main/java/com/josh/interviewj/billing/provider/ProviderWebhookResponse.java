package com.josh.interviewj.billing.provider;

public record ProviderWebhookResponse(
        String body,
        String contentType
) {
}
