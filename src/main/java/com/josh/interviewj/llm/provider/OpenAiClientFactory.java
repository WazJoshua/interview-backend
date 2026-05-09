package com.josh.interviewj.llm.provider;

import com.josh.interviewj.config.LlmProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Creates OpenAI SDK clients for provider adapters.
 */
@Component
public class OpenAiClientFactory {

    /**
     * Builds an SDK client using the configured provider connection settings.
     *
     * @param provider provider configuration
     * @return configured SDK client
     */
    public OpenAIClient create(LlmProperties.ProviderProperties provider) {
        return OpenAIOkHttpClient.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .maxRetries(provider.getMaxRetries())
                .timeout(Duration.ofMillis(provider.getTimeoutMs()))
                .build();
    }
}
