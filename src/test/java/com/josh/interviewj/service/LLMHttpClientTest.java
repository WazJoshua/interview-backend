package com.josh.interviewj.service;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.provider.LLMHttpClient;
import com.josh.interviewj.llm.provider.OpenAiClientFactory;
import com.josh.interviewj.usage.model.UsageFamily;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMHttpClientTest {

    private HttpServer server;
    private ExecutorService executor;

    /**
     * Stops the local mock server after each test.
     */
    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Verifies the SDK-backed adapter extracts assistant content from a chat completion response.
     */
    @Test
    void generateText_ExtractsAssistantContent() throws Exception {
        startServer(200, """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1741689600,
                  "model": "qwen-plus",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "{\\"a\\":1}"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 1,
                    "completion_tokens": 1,
                    "total_tokens": 2
                  }
                }
                """);
        LlmProperties.ProviderProperties providerConfig = provider("test-key");

        LLMHttpClient client = new LLMHttpClient(new OpenAiClientFactory());
        LlmResponse response = client.generateText(providerConfig, "qwen-plus", "sys", "user");

        assertEquals("{\"a\":1}", response.content());
        assertEquals(UsageFamily.CHAT, response.usage().usageFamily());
        assertEquals(1L, response.usage().requestCount());
        assertEquals(1L, response.usage().promptTokens());
        assertEquals(1L, response.usage().completionTokens());
        assertEquals(2L, response.usage().totalTokens());
    }

    /**
     * Verifies unauthorized responses map to a non-retryable auth error.
     */
    @Test
    void generateText_Http401_MapsToNonRetryableLlmException() throws Exception {
        startServer(401, """
                {
                  "error": {
                    "message": "unauthorized",
                    "type": "invalid_request_error",
                    "code": "unauthorized"
                  }
                }
                """);
        LlmProperties.ProviderProperties providerConfig = provider("bad-key");

        LLMHttpClient client = new LLMHttpClient(new OpenAiClientFactory());
        LlmException ex = assertThrows(LlmException.class,
                () -> client.generateText(providerConfig, "qwen", "sys", "user"));

        assertEquals("AUTH", ex.getReason());
        assertFalse(ex.isRetryable());
        assertTrue(ex.getMessage().contains("status=401"));
        assertTrue(ex.getMessage().contains("unauthorized"));
    }

    /**
     * Verifies rate-limit responses map to a retryable provider error.
     */
    @Test
    void generateText_Http429_MapsToRetryableLlmException() throws Exception {
        startServer(429, """
                {
                  "error": {
                    "message": "rate limit",
                    "type": "rate_limit_exceeded",
                    "code": "rate_limit"
                  }
                }
                """);
        LlmProperties.ProviderProperties providerConfig = provider("test-key");

        LLMHttpClient client = new LLMHttpClient(new OpenAiClientFactory());
        LlmException ex = assertThrows(LlmException.class,
                () -> client.generateText(providerConfig, "qwen", "sys", "user"));

        assertEquals("RATE_LIMIT", ex.getReason());
        assertTrue(ex.isRetryable());
        assertTrue(ex.getMessage().contains("status=429"));
        assertTrue(ex.getMessage().contains("rate limit"));
    }

    /**
     * Verifies timeout failures expose configured timeout and root cause details for diagnosis.
     */
    @Test
    void generateText_Timeout_ExposesTimeoutDiagnostics() throws Exception {
        startDelayedServer(200, """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1741689600,
                  "model": "qwen-plus",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "{\\"a\\":1}"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 1,
                    "completion_tokens": 1,
                    "total_tokens": 2
                  }
                }
                """, 300);
        LlmProperties.ProviderProperties providerConfig = provider("test-key", 50);

        LLMHttpClient client = new LLMHttpClient(new OpenAiClientFactory());
        LlmException ex = assertThrows(LlmException.class,
                () -> client.generateText(providerConfig, "qwen", "sys", "user"));

        assertEquals("TIMEOUT", ex.getReason());
        assertTrue(ex.isRetryable());
        assertTrue(ex.getMessage().contains("timeoutMs=50"));
        assertTrue(ex.getMessage().contains("cause="));
    }

    /**
     * Starts a mock OpenAI-compatible chat completions endpoint.
     *
     * @param statusCode HTTP status
     * @param body response body
     * @throws Exception when the server cannot be started
     */
    private void startServer(int statusCode, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/chat/completions", exchange -> {
            byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBytes);
            }
        });
        server.start();
    }

    /**
     * Starts a mock endpoint that delays its response to trigger client-side timeouts.
     *
     * @param statusCode HTTP status
     * @param body       response body
     * @param delayMs    response delay in milliseconds
     * @throws Exception when the server cannot be started
     */
    private void startDelayedServer(int statusCode, String body, long delayMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/chat/completions", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBytes);
            }
        });
        server.start();
    }

    /**
     * Builds provider configuration for the local test server.
     *
     * @param apiKey api key
     * @return provider configuration
     */
    private LlmProperties.ProviderProperties provider(String apiKey) {
        return provider(apiKey, 5000);
    }

    /**
     * Builds provider configuration for the local test server with a custom timeout.
     *
     * @param apiKey    api key
     * @param timeoutMs client timeout in milliseconds
     * @return provider configuration
     */
    private LlmProperties.ProviderProperties provider(String apiKey, int timeoutMs) {
        LlmProperties.ProviderProperties providerProperties = new LlmProperties.ProviderProperties();
        providerProperties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        providerProperties.setApiKey(apiKey);
        providerProperties.setTimeoutMs(timeoutMs);
        providerProperties.setMaxRetries(3);
        providerProperties.setRetryBackoffMs(500);
        LlmProperties.ChatProperties chatProperties = new LlmProperties.ChatProperties();
        LlmProperties.ModelProperties models = new LlmProperties.ModelProperties();
        models.setParse("qwen3.5-27b");
        models.setAnalysis("qwen3.5-35b-a3b");
        chatProperties.setModels(models);
        providerProperties.setChat(chatProperties);
        return providerProperties;
    }
}
