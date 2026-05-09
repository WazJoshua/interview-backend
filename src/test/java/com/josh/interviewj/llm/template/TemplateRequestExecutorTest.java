package com.josh.interviewj.llm.template;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.LlmException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateRequestExecutorTest {

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
    void execute_SendsJsonRequestAndReturnsResponseEnvelope() throws Exception {
        RecordedRequest recordedRequest = new RecordedRequest();
        startServer(200, "{\"data\":[{\"embedding\":[0.1,0.2]}]}", 0L, recordedRequest);

        TemplateRequestExecutor requestExecutor = new TemplateRequestExecutor(JsonMapper.builder().build());
        TemplateHttpResponse response = requestExecutor.execute(provider(1000), new RenderedTemplateRequest(
                "POST",
                "/v1/embeddings",
                Map.of("Authorization", "Bearer test-key"),
                Map.of("purpose", "kb_query_embedding"),
                "{\"input\":\"hello\"}"
        ));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.contentType()).contains("application/json");
        assertThat(recordedRequest.path()).isEqualTo("/v1/embeddings?purpose=kb_query_embedding");
        assertThat(recordedRequest.headers().entrySet().stream()
                .filter(entry -> "content-type".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(List.of()))
                .contains("application/json");
    }

    @Test
    void execute_WhenServerTimesOut_MapsToTimeoutReason() throws Exception {
        startServer(200, "{\"ok\":true}", 300L, new RecordedRequest());

        TemplateRequestExecutor requestExecutor = new TemplateRequestExecutor(JsonMapper.builder().build());
        LlmException exception = assertThrows(LlmException.class, () -> requestExecutor.execute(
                provider(50),
                new RenderedTemplateRequest("POST", "/v1/embeddings", Map.of(), Map.of(), "{\"input\":\"hello\"}")
        ));

        assertThat(exception.getReason()).isEqualTo("TIMEOUT");
    }

    private void startServer(int statusCode, String body, long delayMs, RecordedRequest recordedRequest) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/v1/embeddings", exchange -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            recordedRequest.path(exchange.getRequestURI().toString());
            recordedRequest.headers(Map.copyOf(exchange.getRequestHeaders()));
            byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(responseBytes);
            }
        });
        server.start();
    }

    private LlmProperties.ProviderProperties provider(int timeoutMs) {
        LlmProperties.ProviderProperties provider = new LlmProperties.ProviderProperties();
        provider.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1");
        provider.setApiKey("test-key");
        provider.setTimeoutMs(timeoutMs);
        return provider;
    }

    private static class RecordedRequest {
        private final AtomicReference<String> path = new AtomicReference<>();
        private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>(Map.of());

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
    }
}
