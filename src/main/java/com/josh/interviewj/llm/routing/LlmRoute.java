package com.josh.interviewj.llm.routing;

import com.josh.interviewj.config.LlmProperties;

public record LlmRoute(
        String providerName,
        String purpose,
        String model,
        LlmProperties.ProviderProperties providerConfig
) {
}
