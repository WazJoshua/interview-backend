package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.ragqa.model.DatabaseRerankConfig;
import com.josh.interviewj.ragqa.model.RerankResponse;
import com.josh.interviewj.usage.model.UsageFamily;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);
    private static final String PURPOSE_KB_QUERY_RERANK = "kb_query_rerank";

    private final DatabaseRerankConfigResolver databaseRerankConfigResolver;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RerankResponse rerank(String query, List<String> documents) {
        return rerank(PURPOSE_KB_QUERY_RERANK, query, documents);
    }

    public RerankResponse rerank(String purpose, String query, List<String> documents) {
        List<String> safeDocuments = documents == null ? List.of() : documents;
        if (safeDocuments.isEmpty()) {
            return new RerankResponse(null, 0, List.of(), 0);
        }
        DatabaseRerankConfig config = requireConfig(purpose);
        if (safeDocuments.size() > config.preRerankCandidateCap()) {
            throw new IllegalArgumentException("documents size must be <= preRerankCandidateCap");
        }

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", config.model());
            payload.put("query", query);
            payload.put("top_n", safeDocuments.size());
            ArrayNode documentNodes = payload.putArray("documents");
            safeDocuments.forEach(documentNodes::add);

            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            log.info("event=rerank_request_started model={} candidate_count={}",
                    config.model(), safeDocuments.size());

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new RerankException("Rerank request failed with status=" + response.statusCode());
            }

            RerankResponse rerankResponse = parseResponse(response.body(), config);
            log.info("event=rerank_request_succeeded status={} result_count={}",
                    response.statusCode(), rerankResponse.results().size());
            return rerankResponse;
        } catch (RerankException rerankException) {
            log.warn("event=rerank_request_failed reason=upstream message={}", rerankException.getMessage());
            throw rerankException;
        } catch (HttpTimeoutException timeoutException) {
            log.warn("event=rerank_request_failed reason=timeout message={}", timeoutException.getMessage());
            throw new RerankException("Rerank request timed out", timeoutException);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.warn("event=rerank_request_failed reason=interrupted message={}", interruptedException.getMessage());
            throw new RerankException("Rerank request interrupted", interruptedException);
        } catch (IOException ioException) {
            log.warn("event=rerank_request_failed reason=io_error message={}", ioException.getMessage());
            throw new RerankException("Rerank request failed due to io error", ioException);
        }
    }

    private RerankResponse parseResponse(String body, DatabaseRerankConfig config) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode usageNode = root.path("usage");

        List<RerankResponse.ScoredDocument> scoredDocuments = new ArrayList<>();
        JsonNode resultsNode = root.path("results");
        if (resultsNode.isArray()) {
            for (JsonNode resultNode : resultsNode) {
                int index = resultNode.path("index").asInt(-1);
                double relevanceScore = readDouble(resultNode, "relevance_score", "relevanceScore");
                if (index >= 0) {
                    scoredDocuments.add(new RerankResponse.ScoredDocument(index, relevanceScore));
                }
            }
        }

        String model = readText(root, "model", config.model());
        Integer promptTokens = readInt(root, "prompt_tokens", readInt(usageNode, "prompt_tokens", 0));
        int totalTokens = readInt(root, "total_tokens", readInt(usageNode, "total_tokens", 0));
        return new RerankResponse(
                model,
                promptTokens,
                scoredDocuments,
                totalTokens,
                new ProviderUsage(
                        UsageFamily.RERANK,
                        1L,
                        promptTokens == null ? null : promptTokens.longValue(),
                        null,
                        (long) totalTokens,
                        null
                )
        );
    }

    public String providerKey() {
        return providerKey(PURPOSE_KB_QUERY_RERANK);
    }

    public String providerKey(String purpose) {
        return requireConfig(purpose).providerKey();
    }

    private DatabaseRerankConfig requireConfig(String purpose) {
        return databaseRerankConfigResolver.resolve(purpose)
                .orElseThrow(() -> new IllegalStateException("Rerank route is not configured for purpose: " + purpose));
    }

    private double readDouble(JsonNode node, String snakeCaseField, String camelCaseField) {
        JsonNode snakeNode = node.get(snakeCaseField);
        if (snakeNode != null && snakeNode.isNumber()) {
            return snakeNode.asDouble();
        }
        JsonNode camelNode = node.get(camelCaseField);
        if (camelNode != null && camelNode.isNumber()) {
            return camelNode.asDouble();
        }
        return 0D;
    }

    private int readInt(JsonNode node, String fieldName, int defaultValue) {
        JsonNode valueNode = node.get(fieldName);
        if (valueNode != null && valueNode.isNumber()) {
            return valueNode.asInt();
        }
        return defaultValue;
    }

    private String readText(JsonNode node, String fieldName, String defaultValue) {
        JsonNode valueNode = node.get(fieldName);
        if (valueNode != null && valueNode.isTextual()) {
            return valueNode.asText();
        }
        return defaultValue;
    }
}

class RerankException extends RuntimeException {

    RerankException(String message) {
        super(message);
    }

    RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}
