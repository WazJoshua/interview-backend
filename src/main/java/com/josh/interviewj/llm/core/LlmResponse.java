package com.josh.interviewj.llm.core;

public record LlmResponse(
        String content,
        String provider,
        String model,
        ProviderUsage usage
) {

    public LlmResponse(String content, String provider, String model) {
        this(content, provider, model, null);
    }
}
