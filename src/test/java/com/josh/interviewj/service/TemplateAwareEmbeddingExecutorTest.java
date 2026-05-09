package com.josh.interviewj.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.provider.EmbeddingHttpClient;
import com.josh.interviewj.llm.provider.TemplateAwareEmbeddingExecutor;
import com.josh.interviewj.llm.template.TemplateCapability;
import com.josh.interviewj.llm.template.TemplateDescriptor;
import com.josh.interviewj.llm.template.TemplateHttpResponse;
import com.josh.interviewj.llm.template.TemplateRegistry;
import com.josh.interviewj.llm.template.TemplateRequestExecutor;
import com.josh.interviewj.llm.template.TemplateResponseExtractor;
import com.josh.interviewj.llm.template.TemplateVariables;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateAwareEmbeddingExecutorTest {

    @Mock
    private EmbeddingHttpClient sdkClient;

    @Mock
    private TemplateRegistry templateRegistry;

    @Mock
    private TemplateRequestExecutor templateRequestExecutor;

    @Mock
    private TemplateResponseExtractor templateResponseExtractor;

    @Test
    void generateEmbedding_WhenTemplateHits_PassesTextTypeAndAvoidsFallback() {
        TemplateAwareEmbeddingExecutor executor = new TemplateAwareEmbeddingExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(true, false);
        TemplateDescriptor descriptor = new TemplateDescriptor(
                "Nvidia",
                TemplateCapability.EMBEDDING,
                "classpath:/llm-templates/Nvidia/embedding-request.json",
                "classpath:/llm-templates/Nvidia/embedding-response.yml"
        );
        TemplateHttpResponse response = new TemplateHttpResponse(200, java.util.Map.of(), "application/json", "{\"data\":[{\"embedding\":[0.1,0.2]}]}");

        when(templateRegistry.find("Nvidia", TemplateCapability.EMBEDDING, providerConfig)).thenReturn(Optional.of(descriptor));
        when(templateRequestExecutor.execute(eq(providerConfig), eq(descriptor), any(TemplateVariables.class))).thenReturn(response);
        when(templateResponseExtractor.extractEmbedding(descriptor, response, 2)).thenReturn(new float[]{0.1f, 0.2f});
        when(templateResponseExtractor.extractUsage(descriptor, response, UsageFamily.EMBEDDING, 1L))
                .thenReturn(new ProviderUsage(UsageFamily.EMBEDDING, 1L, 3L, null, 3L, null));

        EmbeddingResponse result = executor.generateEmbedding(
                "Nvidia",
                providerConfig,
                "kb_query_embedding",
                "text-embedding-v4",
                "hello",
                "query",
                2
        );

        assertArrayEquals(new float[]{0.1f, 0.2f}, result.vector());
        assertThat(result.usage()).isNotNull();
        assertEquals(UsageFamily.EMBEDDING, result.usage().usageFamily());
        verify(sdkClient, never()).generateEmbedding(any(), any(), any(), any(), anyInt());

        ArgumentCaptor<TemplateVariables> captor = ArgumentCaptor.forClass(TemplateVariables.class);
        verify(templateRequestExecutor).execute(eq(providerConfig), eq(descriptor), captor.capture());
        assertEquals("query", captor.getValue().textType());
    }

    @Test
    void generateEmbedding_WhenTemplateMissingAndNonStrict_FallsBackToSdk() {
        TemplateAwareEmbeddingExecutor executor = new TemplateAwareEmbeddingExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(true, false);

        when(templateRegistry.find("default", TemplateCapability.EMBEDDING, providerConfig)).thenReturn(Optional.empty());
        when(sdkClient.generateEmbedding(providerConfig, "text-embedding-v4", "hello", "query", 2))
                .thenReturn(new EmbeddingResponse(
                        new float[]{0.1f, 0.2f},
                        "default",
                        "text-embedding-v4",
                        new ProviderUsage(UsageFamily.EMBEDDING, 1L, 2L, null, 2L, null)
                ));

        EmbeddingResponse result = executor.generateEmbedding(
                "default",
                providerConfig,
                "kb_query_embedding",
                "text-embedding-v4",
                "hello",
                "query",
                2
        );

        assertArrayEquals(new float[]{0.1f, 0.2f}, result.vector());
        assertThat(result.usage()).isNotNull();
        verify(sdkClient).generateEmbedding(providerConfig, "text-embedding-v4", "hello", "query", 2);
    }

    @Test
    void generateEmbedding_WhenTemplateDisabled_SkipsRegistryAndFallsBack() {
        TemplateAwareEmbeddingExecutor executor = new TemplateAwareEmbeddingExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(false, false);

        when(sdkClient.generateEmbedding(providerConfig, "text-embedding-v4", "hello", "query", 2))
                .thenReturn(new EmbeddingResponse(
                        new float[]{0.1f, 0.2f},
                        "default",
                        "text-embedding-v4",
                        new ProviderUsage(UsageFamily.EMBEDDING, 1L, 2L, null, 2L, null)
                ));

        EmbeddingResponse result = executor.generateEmbedding(
                "default",
                providerConfig,
                "kb_query_embedding",
                "text-embedding-v4",
                "hello",
                "query",
                2
        );

        assertArrayEquals(new float[]{0.1f, 0.2f}, result.vector());
        verify(templateRegistry, never()).find(any(), any(), any());
    }

    @Test
    void generateEmbedding_WhenTemplateHitFails_DoesNotFallbackToSdk() {
        TemplateAwareEmbeddingExecutor executor = new TemplateAwareEmbeddingExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(true, false);
        TemplateDescriptor descriptor = new TemplateDescriptor(
                "Nvidia",
                TemplateCapability.EMBEDDING,
                "classpath:/llm-templates/Nvidia/embedding-request.json",
                "classpath:/llm-templates/Nvidia/embedding-response.yml"
        );
        TemplateHttpResponse response = new TemplateHttpResponse(200, java.util.Map.of(), "application/json", "{\"oops\":true}");

        when(templateRegistry.find("Nvidia", TemplateCapability.EMBEDDING, providerConfig)).thenReturn(Optional.of(descriptor));
        when(templateRequestExecutor.execute(eq(providerConfig), eq(descriptor), any(TemplateVariables.class))).thenReturn(response);
        when(templateResponseExtractor.extractEmbedding(descriptor, response, 2))
                .thenThrow(new LlmException("invalid", "INVALID_RESPONSE", false));

        assertThrows(LlmException.class,
                () -> executor.generateEmbedding("Nvidia", providerConfig, "kb_query_embedding", "text-embedding-v4", "hello", "query", 2));

        verify(sdkClient, never()).generateEmbedding(any(), any(), any(), any(), anyInt());
    }

    @Test
    void generateEmbedding_WhenTemplateMissingAndStrict_ThrowsAndLogsStrictEvent() {
        TemplateAwareEmbeddingExecutor executor = new TemplateAwareEmbeddingExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(true, true);
        Logger logger = (Logger) LoggerFactory.getLogger(TemplateAwareEmbeddingExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            when(templateRegistry.find("Nvidia", TemplateCapability.EMBEDDING, providerConfig)).thenReturn(Optional.empty());

            assertThrows(BusinessException.class,
                    () -> executor.generateEmbedding("Nvidia", providerConfig, "kb_query_embedding", "text-embedding-v4", "secret-input", "query", 2));

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("template_strict_missing"));
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allMatch(message -> !message.contains("secret-input") && !message.contains("secret-key"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void generateEmbedding_WhenRegistryConfigError_LogsConfigEvent() {
        TemplateAwareEmbeddingExecutor executor = new TemplateAwareEmbeddingExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(true, false);
        BusinessException failure = new BusinessException("LLM_001", "template.root must start with classpath:");
        Logger logger = (Logger) LoggerFactory.getLogger(TemplateAwareEmbeddingExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            when(templateRegistry.find("Nvidia", TemplateCapability.EMBEDDING, providerConfig)).thenThrow(failure);

            assertThrows(BusinessException.class,
                    () -> executor.generateEmbedding("Nvidia", providerConfig, "kb_query_embedding", "text-embedding-v4", "secret-input", "query", 2));

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("template_config_error"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private LlmProperties.ProviderProperties provider(boolean enabled, boolean strict) {
        LlmProperties.ProviderProperties provider = new LlmProperties.ProviderProperties();
        provider.setBaseUrl("https://provider.example.com/v1");
        provider.setApiKey("secret-key");
        provider.setTimeoutMs(1000);
        LlmProperties.TemplateProperties template = new LlmProperties.TemplateProperties();
        template.setEnabled(enabled);
        template.setStrict(strict);
        template.setRoot("classpath:/llm-templates/Nvidia");
        provider.setTemplate(template);
        return provider;
    }
}
