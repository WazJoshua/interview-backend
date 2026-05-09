package com.josh.interviewj.ragqa.model;

import com.josh.interviewj.llm.core.LlmResponse;

public record RewriteResult(
        boolean attempted,
        boolean succeeded,
        String rewrittenText,
        String finalText,
        RewriteFallbackReason fallbackReason,
        boolean preservedTokensSatisfied,
        LlmResponse providerResponse
) {
    public RewriteResult(
            boolean attempted,
            boolean succeeded,
            String rewrittenText,
            String finalText,
            RewriteFallbackReason fallbackReason,
            boolean preservedTokensSatisfied
    ) {
        this(attempted, succeeded, rewrittenText, finalText, fallbackReason, preservedTokensSatisfied, null);
    }

    public static RewriteResult notAttempted(String finalText) {
        return new RewriteResult(false, false, null, finalText, RewriteFallbackReason.NOT_ELIGIBLE, true, null);
    }

    public static RewriteResult failed(String finalText, RewriteFallbackReason fallbackReason) {
        return new RewriteResult(true, false, null, finalText, fallbackReason, false, null);
    }

    public static RewriteResult succeeded(String rewrittenText) {
        return new RewriteResult(true, true, rewrittenText, rewrittenText, null, true, null);
    }

    public static RewriteResult failed(String finalText, RewriteFallbackReason fallbackReason, LlmResponse providerResponse) {
        return new RewriteResult(true, false, null, finalText, fallbackReason, false, providerResponse);
    }

    public static RewriteResult succeeded(String rewrittenText, LlmResponse providerResponse) {
        return new RewriteResult(true, true, rewrittenText, rewrittenText, null, true, providerResponse);
    }
}
