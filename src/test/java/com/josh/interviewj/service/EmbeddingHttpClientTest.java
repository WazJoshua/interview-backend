package com.josh.interviewj.service;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.provider.EmbeddingHttpClient;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingHttpClientTest {

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
     * Verifies the SDK-backed adapter extracts the first embedding vector.
     */
    @Test
    void generateEmbedding_ExtractsVector() throws Exception {
        startServer(200, """
                {
                  "object": "list",
                  "data": [
                    {
                      "object": "embedding",
                      "embedding": [0.1, 0.2],
                      "index": 0
                    }
                  ],
                  "model": "text-embedding-v4",
                  "usage": {
                    "prompt_tokens": 1,
                    "total_tokens": 1
                  }
                }
                """);

        EmbeddingHttpClient client = new EmbeddingHttpClient(new OpenAiClientFactory());

        EmbeddingResponse result = client.generateEmbedding(
                provider("test-key"),
                "text-embedding-v4",
                "Redis persistence?",
                "query",
                2
        );

        assertArrayEquals(new float[]{0.1f, 0.2f}, result.vector());
        assertEquals(UsageFamily.EMBEDDING, result.usage().usageFamily());
        assertEquals(1L, result.usage().requestCount());
        assertEquals(1L, result.usage().promptTokens());
        assertEquals(1L, result.usage().totalTokens());
        assertEquals(null, result.usage().completionTokens());
    }

    /**
     * Verifies unauthorized responses map to a non-retryable auth error.
     */
    @Test
    void generateEmbedding_Http401_MapsToNonRetryableLlmException() throws Exception {
        startServer(401, """
                {
                  "error": {
                    "message": "unauthorized",
                    "type": "invalid_request_error",
                    "code": "unauthorized"
                  }
                }
                """);

        EmbeddingHttpClient client = new EmbeddingHttpClient(new OpenAiClientFactory());
        LlmException ex = assertThrows(LlmException.class,
                () -> client.generateEmbedding(provider("bad-key"), "text-embedding-v4", "Redis persistence?", "query", 2));

        assertEquals("AUTH", ex.getReason());
        assertFalse(ex.isRetryable());
        assertTrue(ex.getMessage().contains("status=401"));
    }

    /**
     * Verifies rate-limit responses map to a retryable provider error.
     */
    @Test
    void generateEmbedding_Http429_MapsToRetryableLlmException() throws Exception {
        startServer(429, """
                {
                  "error": {
                    "message": "rate limit",
                    "type": "rate_limit_exceeded",
                    "code": "rate_limit"
                  }
                }
                """);

        EmbeddingHttpClient client = new EmbeddingHttpClient(new OpenAiClientFactory());
        LlmException ex = assertThrows(LlmException.class,
                () -> client.generateEmbedding(provider("test-key"), "text-embedding-v4", "Redis persistence?", "query", 2));

        assertEquals("RATE_LIMIT", ex.getReason());
        assertTrue(ex.isRetryable());
        assertTrue(ex.getMessage().contains("status=429"));
    }

    /**
     * Verifies dimension mismatch still fails as an invalid provider response.
     */
    @Test
    void generateEmbedding_InvalidDimension_ThrowsNonRetryableLlmException() throws Exception {
        startServer(200, """
                {
                  "object": "list",
                  "data": [
                    {
                      "object": "embedding",
                      "embedding": [0.1],
                      "index": 0
                    }
                  ],
                  "model": "text-embedding-v4",
                  "usage": {
                    "prompt_tokens": 1,
                    "total_tokens": 1
                  }
                }
                """);

        EmbeddingHttpClient client = new EmbeddingHttpClient(new OpenAiClientFactory());
        LlmException ex = assertThrows(LlmException.class,
                () -> client.generateEmbedding(provider("test-key"), "text-embedding-v4", "Redis persistence?", "query", 2));

        assertEquals("INVALID_RESPONSE", ex.getReason());
        assertFalse(ex.isRetryable());
    }

    /**
     * Starts a mock OpenAI-compatible embeddings endpoint.
     *
     * @param statusCode HTTP status
     * @param body response body
     * @throws Exception when the server cannot be started
     */
    private void startServer(int statusCode, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/embeddings", exchange -> {
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
        LlmProperties.ProviderProperties providerProperties = new LlmProperties.ProviderProperties();
        providerProperties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        providerProperties.setApiKey(apiKey);
        providerProperties.setTimeoutMs(5000);
        providerProperties.setMaxRetries(3);
        providerProperties.setRetryBackoffMs(500);

        LlmProperties.EmbeddingProperties embeddingProperties = new LlmProperties.EmbeddingProperties();
        embeddingProperties.setDimension(2);
        LlmProperties.ModelProperties models = new LlmProperties.ModelProperties();
        models.put("kb_query_embedding", "text-embedding-v4");
        embeddingProperties.setModels(models);
        providerProperties.setEmbedding(embeddingProperties);
        return providerProperties;
    }
}
