package com.josh.interviewj.llm.routing;

import com.josh.interviewj.config.LlmProperties;

public record EmbeddingRoute(
        String providerName,
        String purpose,
        String model,
        String inputType,
        int dimension,
        LlmProperties.ProviderProperties providerConfig
) {
}
