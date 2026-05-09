package com.josh.interviewj.service;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.provider.LLMHttpClient;
import com.josh.interviewj.llm.provider.OpenAiClientFactory;
import com.josh.interviewj.llm.provider.TemplateAwareLlmExecutor;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TemplateAwareLlmExecutorIntegrationTest {

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
    void generateText_WhenTemplateHits_UsesDispatcherTemplateRequest() throws Exception {
        RecordedExchange recordedExchange = new RecordedExchange();
        startServer("/v1/chat/completions", 200, """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"ok\\":true}"
                      }
                    }
                  ]
                }
                """, recordedExchange);

        LLMHttpClient sdkClient = mock(LLMHttpClient.class);
        TemplateAwareLlmExecutor executor = createExecutor(sdkClient);

        LlmResponse result = executor.generateText(
                "dispatcher_rc",
                dispatcherProvider(),
                "rag",
                "gpt-5.1-codex-mini",
                "sys",
                "user"
        );

        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        assertThat(recordedExchange.path()).isEqualTo("/v1/chat/completions");
        assertThat(recordedExchange.body()).contains("\"role\":\"system\"");
        assertThat(firstHeader(recordedExchange.headers(), "Authorization")).isEqualTo("Bearer test-key");
        verify(sdkClient, never()).generateText(any(), anyString(), anyString(), anyString());
    }

    @Test
    void generateText_WhenTemplateMissing_FallsBackToSdkClient() throws Exception {
        startServer("/chat/completions", 200, """
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
                        "content": "{\\"fallback\\":true}"
                      }
                    }
                  ]
                }
                """, new RecordedExchange());

        TemplateAwareLlmExecutor executor = createExecutor(new LLMHttpClient(new OpenAiClientFactory()));

        LlmResponse result = executor.generateText("default", defaultProvider(), "parse", "qwen-plus", "sys", "user");

        assertThat(result.content()).isEqualTo("{\"fallback\":true}");
    }

    @Test
    void generateText_WhenTemplateHitGetsNonJsonErrorBody_FailsFastWithoutFallback() throws Exception {
        startServer("/v1/chat/completions", 500, "upstream failed", new RecordedExchange());

        LLMHttpClient sdkClient = mock(LLMHttpClient.class);
        TemplateAwareLlmExecutor executor = createExecutor(sdkClient);

        assertThatThrownBy(() -> executor.generateText(
                "dispatcher_rc",
                dispatcherProvider(),
                "rag",
                "gpt-5.1-codex-mini",
                "sys",
                "user"
        ))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("valid JSON");

        verify(sdkClient, never()).generateText(any(), anyString(), anyString(), anyString());
    }

    private TemplateAwareLlmExecutor createExecutor(LLMHttpClient sdkClient) {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        return new TemplateAwareLlmExecutor(
                sdkClient,
                new ClasspathTemplateRegistry(new DefaultResourceLoader()),
                new TemplateRequestExecutor(objectMapper),
                new TemplateResponseExtractor(objectMapper)
        );
    }

    private LlmProperties.ProviderProperties dispatcherProvider() {
        LlmProperties.ProviderProperties provider = new LlmProperties.ProviderProperties();
        provider.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        provider.setApiKey("test-key");
        provider.setTimeoutMs(1000);
        LlmProperties.TemplateProperties template = new LlmProperties.TemplateProperties();
        template.setEnabled(true);
        template.setStrict(true);
        template.setRoot("classpath:/llm-templates/dispatcher_rc");
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
        LlmProperties.ChatProperties chatProperties = new LlmProperties.ChatProperties();
        LlmProperties.ModelProperties models = new LlmProperties.ModelProperties();
        models.setParse("qwen-plus");
        chatProperties.setModels(models);
        provider.setChat(chatProperties);
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
