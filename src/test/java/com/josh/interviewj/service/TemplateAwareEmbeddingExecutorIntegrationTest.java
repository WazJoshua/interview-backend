package com.josh.interviewj.service;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.provider.EmbeddingHttpClient;
import com.josh.interviewj.llm.provider.OpenAiClientFactory;
import com.josh.interviewj.llm.provider.TemplateAwareEmbeddingExecutor;
import com.josh.interviewj.llm.template.ClasspathTemplateRegistry;
import com.josh.interviewj.llm.template.TemplateRequestExecutor;
import com.josh.interviewj.llm.template.TemplateResponseExtractor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TemplateAwareEmbeddingExecutorIntegrationTest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void generateEmbedding_WhenTemplateHits_QueryBodyContainsQueryTextType() throws Exception {
        RecordedExchange recordedExchange = new RecordedExchange();
        startServer("/v1/embeddings", 200, """
                {
                  "data": [
                    {
                      "embedding": [0.1, 0.2]
                    }
                  ]
                }
                """, recordedExchange);

        EmbeddingHttpClient sdkClient = mock(EmbeddingHttpClient.class);
        TemplateAwareEmbeddingExecutor executor = createExecutor(sdkClient);

        EmbeddingResponse vector = executor.generateEmbedding(
                "Nvidia",
                nvidiaProvider(),
                "kb_query_embedding",
                "text-embedding-v4",
                "hello",
                "query",
                2
        );

        assertThat(vector.vector()).containsExactly(0.1f, 0.2f);
        assertThat(vector.usage()).isNotNull();
        assertThat(recordedExchange.body()).contains("\"input_type\":\"query\"");
        assertThat(firstHeader(recordedExchange.headers(), "Authorization")).isEqualTo("Bearer test-key");
        verify(sdkClient, never()).generateEmbedding(any(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void generateEmbedding_WhenTemplateHits_DocumentBodyContainsPassageTextType() throws Exception {
        RecordedExchange recordedExchange = new RecordedExchange();
        startServer("/v1/embeddings", 200, """
                {
                  "data": [
                    {
                      "embedding": [0.3, 0.4]
                    }
                  ]
                }
                """, recordedExchange);

        EmbeddingHttpClient sdkClient = mock(EmbeddingHttpClient.class);
        TemplateAwareEmbeddingExecutor executor = createExecutor(sdkClient);

        EmbeddingResponse vector = executor.generateEmbedding(
                "Nvidia",
                nvidiaProvider(),
                "kb_document_embedding",
                "nvidia/llama-nemotron-embed-1b-v2",
                "world",
                "passage",
                2
        );

        assertThat(vector.vector()).containsExactly(0.3f, 0.4f);
        assertThat(recordedExchange.body()).contains("\"input_type\":\"passage\"");
    }

    @Test
    void generateEmbedding_WhenTemplateMissing_FallsBackToSdkClient() throws Exception {
        startServer("/embeddings", 200, """
                {
                  "data": [
                    {
                      "embedding": [0.1, 0.2]
                    }
                  ]
                }
                """, new RecordedExchange());

        TemplateAwareEmbeddingExecutor executor = createExecutor(new EmbeddingHttpClient(new OpenAiClientFactory()));

        EmbeddingResponse vector = executor.generateEmbedding(
                "default",
                defaultProvider(),
                "kb_query_embedding",
                "text-embedding-v4",
                "hello",
                "query",
                2
        );

        assertThat(vector.vector()).containsExactly(0.1f, 0.2f);
    }

    @Test
    void generateEmbedding_WhenTemplateHitGetsNonJsonErrorBody_FailsFastWithoutFallback() throws Exception {
        startServer("/v1/embeddings", 500, "upstream failed", new RecordedExchange());

        EmbeddingHttpClient sdkClient = mock(EmbeddingHttpClient.class);
        TemplateAwareEmbeddingExecutor executor = createExecutor(sdkClient);

        assertThatThrownBy(() -> executor.generateEmbedding(
                "Nvidia",
                nvidiaProvider(),
                "kb_query_embedding",
                "text-embedding-v4",
                "hello",
                "query",
                2
        ))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("valid JSON");

        verify(sdkClient, never()).generateEmbedding(any(), anyString(), anyString(), anyString(), anyInt());
    }

    private TemplateAwareEmbeddingExecutor createExecutor(EmbeddingHttpClient sdkClient) {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        return new TemplateAwareEmbeddingExecutor(
                sdkClient,
                new ClasspathTemplateRegistry(new DefaultResourceLoader()),
                new TemplateRequestExecutor(objectMapper),
                new TemplateResponseExtractor(objectMapper)
        );
    }

    private LlmProperties.ProviderProperties nvidiaProvider() {
        LlmProperties.ProviderProperties provider = new LlmProperties.ProviderProperties();
        provider.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        provider.setApiKey("test-key");
        provider.setTimeoutMs(1000);
        LlmProperties.TemplateProperties template = new LlmProperties.TemplateProperties();
        template.setEnabled(true);
        template.setStrict(true);
        template.setRoot("classpath:/llm-templates/Nvidia");
        provider.setTemplate(template);
        return provider;
    }

    private LlmProperties.ProviderProperties defaultProvider() {
        LlmProperties.ProviderProperties provider = new LlmProperties.ProviderProperties();
        provider.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        provider.setApiKey("test-key");
        provider.setTimeoutMs(1000);
        LlmProperties.TemplateProperties template = new LlmProperties.TemplateProperties();
        template.setEnabled(true);
        template.setStrict(false);
        provider.setTemplate(template);
        LlmProperties.EmbeddingProperties embeddingProperties = new LlmProperties.EmbeddingProperties();
        embeddingProperties.setDimension(2);
        LlmProperties.ModelProperties models = new LlmProperties.ModelProperties();
        models.put("kb_query_embedding", "text-embedding-v4");
        embeddingProperties.setModels(models);
        provider.setEmbedding(embeddingProperties);
        return provider;
    }

    private void startServer(String path, int statusCode, String responseBody, RecordedExchange recordedExchange) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext(path, exchange -> handleExchange(exchange, statusCode, responseBody, recordedExchange));
        server.start();
    }

    private void handleExchange(HttpExchange exchange, int statusCode, String responseBody, RecordedExchange recordedExchange) throws IOException {
        recordedExchange.path(exchange.getRequestURI().toString());
        recordedExchange.headers(Map.copyOf(exchange.getRequestHeaders()));
        recordedExchange.body(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        if (statusCode >= 400) {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
        } else {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private String firstHeader(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(entry -> entry.getValue().getFirst())
                .findFirst()
                .orElse(null);
    }

    private static class RecordedExchange {
        private final AtomicReference<String> path = new AtomicReference<>();
        private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>(Map.of());
        private final AtomicReference<String> body = new AtomicReference<>();

        String path() {
            return path.get();
        }

        void path(String value) {
            path.set(value);
        }

        Map<String, List<String>> headers() {
            return headers.get();
        }

        void headers(Map<String, List<String>> value) {
            headers.set(value);
        }

        String body() {
            return body.get();
        }

        void body(String value) {
            body.set(value);
        }
    }
}
