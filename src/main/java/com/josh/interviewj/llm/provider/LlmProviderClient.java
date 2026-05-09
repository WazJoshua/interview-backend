package com.josh.interviewj.llm.provider;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.LlmResponse;

public interface LlmProviderClient {

    String provider();

    LlmResponse generateText(
            LlmProperties.ProviderProperties providerConfig,
            String model,
            String systemPrompt,
            String userPrompt
    );
}
