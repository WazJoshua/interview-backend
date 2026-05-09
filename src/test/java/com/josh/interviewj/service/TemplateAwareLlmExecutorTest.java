package com.josh.interviewj.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.provider.LLMHttpClient;
import com.josh.interviewj.llm.provider.TemplateAwareLlmExecutor;
import com.josh.interviewj.llm.template.TemplateCapability;
import com.josh.interviewj.llm.template.TemplateDescriptor;
import com.josh.interviewj.llm.template.TemplateHttpResponse;
import com.josh.interviewj.llm.template.TemplateRegistry;
import com.josh.interviewj.llm.template.TemplateRequestExecutor;
import com.josh.interviewj.llm.template.TemplateResponseExtractor;
import com.josh.interviewj.llm.template.TemplateVariables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateAwareLlmExecutorTest {

    @Mock
    private LLMHttpClient sdkClient;

    @Mock
    private TemplateRegistry templateRegistry;

    @Mock
    private TemplateRequestExecutor templateRequestExecutor;

    @Mock
    private TemplateResponseExtractor templateResponseExtractor;

    @Test
    void generateText_WhenTemplateHits_UsesTemplatePathWithoutFallback() {
        TemplateAwareLlmExecutor executor = new TemplateAwareLlmExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(true, false);
        TemplateDescriptor descriptor = new TemplateDescriptor(
                "dispatcher_rc",
                TemplateCapability.CHAT,
                "classpath:/llm-templates/dispatcher_rc/chat-request.json",
                "classpath:/llm-templates/dispatcher_rc/chat-response.yml"
        );
        TemplateHttpResponse response = new TemplateHttpResponse(200, java.util.Map.of(), "application/json", "{\"ok\":true}");

        when(templateRegistry.find("dispatcher_rc", TemplateCapability.CHAT, providerConfig)).thenReturn(Optional.of(descriptor));
        when(templateRequestExecutor.execute(eq(providerConfig), eq(descriptor), any(TemplateVariables.class))).thenReturn(response);
        when(templateResponseExtractor.extractChatContent(descriptor, response)).thenReturn("{\"ok\":true}");

        LlmResponse result = executor.generateText("dispatcher_rc", providerConfig, "rag", "qwen", "sys", "user");

        assertEquals("{\"ok\":true}", result.content());
        verify(sdkClient, never()).generateText(any(), any(), any(), any());
        verify(templateRequestExecutor).execute(eq(providerConfig), eq(descriptor), any(TemplateVariables.class));
    }

    @Test
    void generateText_WhenTemplateMissingAndNonStrict_FallsBackToSdk() {
        TemplateAwareLlmExecutor executor = new TemplateAwareLlmExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(true, false);

        when(templateRegistry.find("default", TemplateCapability.CHAT, providerConfig)).thenReturn(Optional.empty());
        when(sdkClient.generateText(providerConfig, "qwen", "sys", "user"))
                .thenReturn(new LlmResponse("{\"fallback\":true}", "default", "qwen"));

        LlmResponse result = executor.generateText("default", providerConfig, "parse", "qwen", "sys", "user");

        assertEquals("{\"fallback\":true}", result.content());
        verify(sdkClient).generateText(providerConfig, "qwen", "sys", "user");
    }

    @Test
    void generateText_WhenTemplateDisabled_SkipsRegistryAndFallsBack() {
        TemplateAwareLlmExecutor executor = new TemplateAwareLlmExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(false, false);

        when(sdkClient.generateText(providerConfig, "qwen", "sys", "user"))
                .thenReturn(new LlmResponse("{\"fallback\":true}", "default", "qwen"));

        LlmResponse result = executor.generateText("default", providerConfig, "parse", "qwen", "sys", "user");

        assertEquals("{\"fallback\":true}", result.content());
        verify(templateRegistry, never()).find(any(), any(), any());
    }

    @Test
    void generateText_WhenTemplateMissingAndStrict_ThrowsBusinessException() {
        TemplateAwareLlmExecutor executor = new TemplateAwareLlmExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(true, true);

        when(templateRegistry.find("dispatcher_rc", TemplateCapability.CHAT, providerConfig)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> executor.generateText("dispatcher_rc", providerConfig, "rag", "qwen", "sys", "user")
        );

        assertEquals("LLM_001", exception.getErrorCode());
        verify(sdkClient, never()).generateText(any(), any(), any(), any());
    }

    @Test
    void generateText_WhenRegistryConfigError_LogsSanitizedConfigEvent() {
        TemplateAwareLlmExecutor executor = new TemplateAwareLlmExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(true, false);
        BusinessException failure = new BusinessException("LLM_001", "template.root must start with classpath:");
        Logger logger = (Logger) LoggerFactory.getLogger(TemplateAwareLlmExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            when(templateRegistry.find("dispatcher_rc", TemplateCapability.CHAT, providerConfig)).thenThrow(failure);

            assertThrows(BusinessException.class,
                    () -> executor.generateText("dispatcher_rc", providerConfig, "rag", "qwen", "secret-sys", "secret-user"));

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("template_config_error"));
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allMatch(message -> !message.contains("secret-sys") && !message.contains("secret-user") && !message.contains("secret-key"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void generateText_WhenTemplateHits_PassesPurposeAndPromptsToVariables() {
        TemplateAwareLlmExecutor executor = new TemplateAwareLlmExecutor(
                sdkClient,
                templateRegistry,
                templateRequestExecutor,
                templateResponseExtractor
        );
        LlmProperties.ProviderProperties providerConfig = provider(true, false);
        TemplateDescriptor descriptor = new TemplateDescriptor(
                "dispatcher_rc",
                TemplateCapability.CHAT,
                "classpath:/llm-templates/dispatcher_rc/chat-request.json",
                "classpath:/llm-templates/dispatcher_rc/chat-response.yml"
        );
        TemplateHttpResponse response = new TemplateHttpResponse(200, java.util.Map.of(), "application/json", "{\"ok\":true}");

        when(templateRegistry.find("dispatcher_rc", TemplateCapability.CHAT, providerConfig)).thenReturn(Optional.of(descriptor));
        when(templateRequestExecutor.execute(eq(providerConfig), eq(descriptor), any(TemplateVariables.class))).thenReturn(response);
        when(templateResponseExtractor.extractChatContent(descriptor, response)).thenReturn("{\"ok\":true}");

        executor.generateText("dispatcher_rc", providerConfig, "rag", "qwen", "sys", "user");

        ArgumentCaptor<TemplateVariables> captor = ArgumentCaptor.forClass(TemplateVariables.class);
        verify(templateRequestExecutor).execute(eq(providerConfig), eq(descriptor), captor.capture());
        assertEquals("rag", captor.getValue().purpose());
        assertEquals("sys", captor.getValue().systemPrompt());
        assertEquals("user", captor.getValue().userPrompt());
    }

    private LlmProperties.ProviderProperties provider(boolean enabled, boolean strict) {
        LlmProperties.ProviderProperties provider = new LlmProperties.ProviderProperties();
        provider.setBaseUrl("https://provider.example.com/v1");
        provider.setApiKey("secret-key");
        provider.setTimeoutMs(1000);
        LlmProperties.TemplateProperties template = new LlmProperties.TemplateProperties();
        template.setEnabled(enabled);
        template.setStrict(strict);
        template.setRoot("classpath:/llm-templates/dispatcher_rc");
        provider.setTemplate(template);
        return provider;
    }
}
