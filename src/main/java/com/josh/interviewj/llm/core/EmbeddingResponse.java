package com.josh.interviewj.llm.core;

public record EmbeddingResponse(
        float[] vector,
        String provider,
        String model,
        ProviderUsage usage
) {

    public EmbeddingResponse(float[] vector, String provider, String model) {
        this(vector, provider, model, null);
    }
}
