package com.josh.interviewj.llm.provider;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.template.TemplateCapability;
import com.josh.interviewj.llm.template.TemplateDescriptor;
import com.josh.interviewj.llm.template.TemplateHttpResponse;
import com.josh.interviewj.llm.template.TemplateRegistry;
import com.josh.interviewj.llm.template.TemplateRequestExecutor;
import com.josh.interviewj.llm.template.TemplateResponseExtractor;
import com.josh.interviewj.llm.template.TemplateVariables;
import com.josh.interviewj.usage.model.UsageFamily;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class TemplateAwareEmbeddingExecutor {

    private final EmbeddingHttpClient sdkClient;
    private final TemplateRegistry templateRegistry;
    private final TemplateRequestExecutor templateRequestExecutor;
    private final TemplateResponseExtractor templateResponseExtractor;

    public EmbeddingResponse generateEmbedding(
            String providerName,
            LlmProperties.ProviderProperties providerConfig,
            String purpose,
            String model,
            String input,
            String textType,
            int dimensions
    ) {
        if (providerConfig.getTemplate() == null || !providerConfig.getTemplate().isEnabled()) {
            log.info("event=template_miss_fallback_sdk capability=embedding provider={} purpose={} model={}",
                    providerName, purpose, model);
            return sdkClient.generateEmbedding(providerConfig, model, input, textType, dimensions);
        }

        Optional<TemplateDescriptor> descriptor;
        try {
            descriptor = templateRegistry.find(providerName, TemplateCapability.EMBEDDING, providerConfig);
        } catch (BusinessException exception) {
            log.warn("event=template_config_error capability=embedding provider={} purpose={} model={} message={}",
                    providerName, purpose, model, exception.getMessage());
            throw exception;
        }

        if (descriptor.isEmpty()) {
            if (providerConfig.getTemplate().isStrict()) {
                log.warn("event=template_strict_missing capability=embedding provider={} purpose={} model={}",
                        providerName, purpose, model);
                throw new BusinessException("LLM_001", "Template is required but missing for provider: " + providerName);
            }
            log.info("event=template_miss_fallback_sdk capability=embedding provider={} purpose={} model={}",
                    providerName, purpose, model);
            return sdkClient.generateEmbedding(providerConfig, model, input, textType, dimensions);
        }

        log.info("event=template_hit capability=embedding provider={} purpose={} model={}",
                providerName, purpose, model);
        TemplateHttpResponse response = templateRequestExecutor.execute(
                providerConfig,
                descriptor.get(),
                new TemplateVariables(
                        providerConfig.getBaseUrl(),
                        providerConfig.getApiKey(),
                        providerName,
                        purpose,
                        model,
                        providerConfig.getTimeoutMs(),
                        null,
                        null,
                        input,
                        textType,
                        dimensions
                )
        );
        float[] vector = templateResponseExtractor.extractEmbedding(descriptor.get(), response, dimensions);
        ProviderUsage usage = templateResponseExtractor.extractUsage(
                descriptor.get(),
                response,
                UsageFamily.EMBEDDING,
                1L
        );
        return new EmbeddingResponse(vector, providerName, model, usage);
    }
}
