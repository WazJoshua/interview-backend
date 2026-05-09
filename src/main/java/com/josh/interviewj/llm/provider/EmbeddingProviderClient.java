package com.josh.interviewj.llm.provider;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.EmbeddingResponse;

public interface EmbeddingProviderClient {

    String provider();

    EmbeddingResponse generateEmbedding(
            LlmProperties.ProviderProperties providerConfig,
            String model,
            String input,
            String textType,
            int dimensions
    );
}
