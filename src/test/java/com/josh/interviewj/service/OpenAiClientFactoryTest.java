package com.josh.interviewj.service;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.provider.OpenAiClientFactory;
import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenAiClientFactoryTest {

    /**
     * Verifies the factory creates an SDK client from provider configuration.
     */
    @Test
    void create_BuildsClientFromProviderConfiguration() {
        LlmProperties.ProviderProperties provider = new LlmProperties.ProviderProperties();
        provider.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        provider.setApiKey("test-key");

        OpenAiClientFactory factory = new OpenAiClientFactory();
        OpenAIClient client = factory.create(provider);

        assertNotNull(client);
    }
}
