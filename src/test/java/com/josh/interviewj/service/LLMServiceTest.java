package com.josh.interviewj.service;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.LlmRequest;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.provider.TemplateAwareLlmExecutor;
import com.josh.interviewj.llm.routing.LlmRoute;
import com.josh.interviewj.llm.routing.LlmRouter;
import com.josh.interviewj.llm.support.LlmPurposeCircuitBreakerRegistry;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LLMServiceTest {

    private static final String PURPOSE_PARSE = "parse";
    private static final String PURPOSE_ANALYSIS = "analysis";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private LlmRouter llmRouter;

    @Mock
    private TemplateAwareLlmExecutor templateAwareLlmExecutor;

    private LlmPurposeCircuitBreakerRegistry breakerRegistry;

    @BeforeEach
    void setUp() {
        breakerRegistry = new LlmPurposeCircuitBreakerRegistry(5, 30, 60);
    }

    /**
     * Verifies parse generation uses the parse route and normalizes raw JSON.
     */
    @Test
    void generateParseStructuredJson_UsesParsePurposeAndNormalizesJson() {
        when(llmRouter.resolve(PURPOSE_PARSE)).thenReturn(route("qwen-parse", 1, 1, "test-key"));
        when(templateAwareLlmExecutor.generateText(any(), any(), any(), any(), any(), any()))
                .thenReturn(llmResponse("{\"a\": 1}", 3L, 5L, 8L));

        LLMService service = new LLMService(objectMapper, llmRouter, templateAwareLlmExecutor, breakerRegistry);
        String json = service.generateParseStructuredJson("sys", "user");
        LlmResponse response = service.generateStructuredJson(new LlmRequest(PURPOSE_PARSE, "sys", "user"));

        assertEquals("{\"a\":1}", json);
        assertEquals(UsageFamily.CHAT, response.usage().usageFamily());
        assertEquals(8L, response.usage().totalTokens());
        verify(llmRouter, times(2)).resolve(PURPOSE_PARSE);
        verify(templateAwareLlmExecutor, times(2)).generateText(any(), any(), any(), any(), any(), any());
    }

    /**
     * Verifies analysis generation uses the analysis route.
     */
    @Test
    void generateAnalysisStructuredJson_UsesAnalysisPurpose() {
        when(llmRouter.resolve(PURPOSE_ANALYSIS)).thenReturn(route("qwen-analysis", 1, 1, "test-key"));
        when(templateAwareLlmExecutor.generateText(any(), any(), any(), any(), any(), any()))
                .thenReturn(llmResponse("{\"score\": 95}", 2L, 4L, 6L));

        LLMService service = new LLMService(objectMapper, llmRouter, templateAwareLlmExecutor, breakerRegistry);
        String json = service.generateAnalysisStructuredJson("sys", "user");

        assertEquals("{\"score\":95}", json);
        verify(llmRouter).resolve(PURPOSE_ANALYSIS);
    }

    /**
     * Verifies markdown-fenced JSON is still extracted after adapter simplification.
     */
    @Test
    void generateStructuredJson_ExtractsMarkdownFencedJson() {
        when(llmRouter.resolve(PURPOSE_PARSE)).thenReturn(route("qwen-parse", 1, 1, "test-key"));
        when(templateAwareLlmExecutor.generateText(any(), any(), any(), any(), any(), any()))
                .thenReturn(llmResponse("```json\n{\"score\":95}\n```", 2L, 4L, 6L));

        LLMService service = new LLMService(objectMapper, llmRouter, templateAwareLlmExecutor, breakerRegistry);
        String json = service.generateParseStructuredJson("sys", "user");

        assertEquals("{\"score\":95}", json);
    }

    /**
     * Verifies retryable provider failures are retried.
     */
    @Test
    void generateStructuredJson_RetriesOnRetryableProviderFailure() {
        when(llmRouter.resolve(PURPOSE_PARSE)).thenReturn(route("qwen-parse", 2, 1, "test-key"));
        when(templateAwareLlmExecutor.generateText(any(), any(), any(), any(), any(), any()))
                .thenThrow(new LlmException("temporary", "SERVER", true))
                .thenReturn(llmResponse("{\"status\":\"ok\"}", 2L, 4L, 6L));

        LLMService service = new LLMService(objectMapper, llmRouter, templateAwareLlmExecutor, breakerRegistry);
        String json = service.generateParseStructuredJson("sys", "user");

        assertTrue(json.contains("\"status\":\"ok\""));
        verify(templateAwareLlmExecutor, times(2)).generateText(any(), any(), any(), any(), any(), any());
    }

    /**
     * Verifies non-retryable provider failures stop immediately.
     */
    @Test
    void generateStructuredJson_NoRetryOnNonRetryableProviderFailure() {
        when(llmRouter.resolve(PURPOSE_PARSE)).thenReturn(route("qwen-parse", 3, 1, "test-key"));
        when(templateAwareLlmExecutor.generateText(any(), any(), any(), any(), any(), any()))
                .thenThrow(new LlmException("auth failed", "AUTH", false));

        LLMService service = new LLMService(objectMapper, llmRouter, templateAwareLlmExecutor, breakerRegistry);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateParseStructuredJson("sys", "user"));
        assertEquals("LLM_001", ex.getErrorCode());
        assertEquals("LLM service call failed: AUTH - auth failed", ex.getMessage());
        verify(templateAwareLlmExecutor, times(1)).generateText(any(), any(), any(), any(), any(), any());
    }

    /**
     * Verifies missing API keys fail fast before calling the provider.
     */
    @Test
    void generateStructuredJson_MissingApiKey_ThrowsBusinessException() {
        when(llmRouter.resolve(PURPOSE_PARSE)).thenReturn(route("qwen-parse", 1, 1, " "));

        LLMService service = new LLMService(objectMapper, llmRouter, templateAwareLlmExecutor, breakerRegistry);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateParseStructuredJson("sys", "user"));

        assertEquals("LLM_001", ex.getErrorCode());
    }

    /**
     * Verifies requests require a non-blank purpose.
     */
    @Test
    void generateStructuredJson_RequestPurposeRequired() {
        LLMService service = new LLMService(objectMapper, llmRouter, templateAwareLlmExecutor, breakerRegistry);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateStructuredJson(new LlmRequest((String) null, "sys", "user")));
        assertEquals("LLM_001", ex.getErrorCode());
    }

    /**
     * Verifies prompts are passed to the provider unchanged.
     */
    @Test
    void generateStructuredJson_PassesPromptsToProvider() {
        when(llmRouter.resolve(PURPOSE_PARSE)).thenReturn(route("qwen-parse", 1, 1, "test-key"));
        when(templateAwareLlmExecutor.generateText(any(), any(), any(), any(), any(), any()))
                .thenReturn(llmResponse("{\"ok\":true}", 2L, 4L, 6L));

        LLMService service = new LLMService(objectMapper, llmRouter, templateAwareLlmExecutor, breakerRegistry);
        service.generateParseStructuredJson("system prompt", "user prompt");

        ArgumentCaptor<String> providerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> purposeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(templateAwareLlmExecutor).generateText(
                providerCaptor.capture(),
                any(),
                purposeCaptor.capture(),
                modelCaptor.capture(),
                systemCaptor.capture(),
                userCaptor.capture()
        );
        assertEquals("default", providerCaptor.getValue());
        assertEquals(PURPOSE_PARSE, purposeCaptor.getValue());
        assertEquals("qwen-parse", modelCaptor.getValue());
        assertEquals("system prompt", systemCaptor.getValue());
        assertEquals("user prompt", userCaptor.getValue());
    }

    /**
     * Verifies circuit breaker rejects requests when open.
     */
    @Test
    void generateStructuredJson_CircuitBreakerOpen_RejectsRequest() {
        when(llmRouter.resolve(PURPOSE_PARSE)).thenReturn(route("qwen-parse", 1, 1, "test-key"));

        // Create a registry with threshold of 1 to trigger open after first failure
        LlmPurposeCircuitBreakerRegistry strictRegistry = new LlmPurposeCircuitBreakerRegistry(1, 30, 60);
        LLMService service = new LLMService(objectMapper, llmRouter, templateAwareLlmExecutor, strictRegistry);

        // Record a failure to trip the breaker
        when(templateAwareLlmExecutor.generateText(any(), any(), any(), any(), any(), any()))
                .thenThrow(new LlmException("error", "SERVER", true));

        assertThrows(BusinessException.class, () -> service.generateParseStructuredJson("sys", "user"));

        // Now the breaker should be open - next call should be rejected
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateParseStructuredJson("sys", "user"));
        assertEquals("LLM_002", ex.getErrorCode());
    }

    @Test
    void generateStructuredJson_SchemaValidationFailure_CountsAsBreakerFailure() {
        when(llmRouter.resolve(PURPOSE_PARSE)).thenReturn(route("qwen-parse", 1, 1, "test-key"));
        when(templateAwareLlmExecutor.generateText(any(), any(), any(), any(), any(), any()))
                .thenReturn(llmResponse("{\"ok\":true}", 2L, 4L, 6L));

        LlmPurposeCircuitBreakerRegistry strictRegistry = new LlmPurposeCircuitBreakerRegistry(1, 30, 60);
        LLMService service = new LLMService(objectMapper, llmRouter, templateAwareLlmExecutor, strictRegistry);

        assertThrows(BusinessException.class, () -> service.generateStructuredJson(
                new LlmRequest(PURPOSE_PARSE, "sys", "user"),
                json -> {
                    throw new BusinessException("LLM_003", "schema failed");
                }
        ));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.generateParseStructuredJson("sys", "user"));
        assertEquals("LLM_002", ex.getErrorCode());
    }

    /**
     * Builds a routed provider fixture for LLM service tests.
     *
     * @param model resolved model
     * @param maxRetries max retries
     * @param backoffMs retry backoff in milliseconds
     * @param apiKey provider API key
     * @return test route
     */
    private LlmRoute route(String model, int maxRetries, int backoffMs, String apiKey) {
        LlmProperties.ProviderProperties providerProperties = new LlmProperties.ProviderProperties();
        providerProperties.setApiKey(apiKey);
        providerProperties.setBaseUrl("http://localhost:8080");
        providerProperties.setMaxRetries(maxRetries);
        providerProperties.setRetryBackoffMs(backoffMs);
        providerProperties.setTimeoutMs(2000);
        LlmProperties.ChatProperties chatProperties = new LlmProperties.ChatProperties();
        LlmProperties.ModelProperties modelProperties = new LlmProperties.ModelProperties();
        modelProperties.put("parse", "qwen-parse");
        modelProperties.put("analysis", "qwen-analysis");
        chatProperties.setModels(modelProperties);
        providerProperties.setChat(chatProperties);
        return new LlmRoute("default", PURPOSE_PARSE, model, providerProperties);
    }

    private LlmResponse llmResponse(String content, long promptTokens, long completionTokens, long totalTokens) {
        return new LlmResponse(
                content,
                "default",
                "mock-model",
                new ProviderUsage(UsageFamily.CHAT, 1L, promptTokens, completionTokens, totalTokens, null)
        );
    }
}
