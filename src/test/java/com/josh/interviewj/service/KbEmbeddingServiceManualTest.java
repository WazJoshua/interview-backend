package com.josh.interviewj.service;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.knowledgebase.service.KbEmbeddingService;
import com.josh.interviewj.llm.EmbeddingService;
import com.josh.interviewj.llm.provider.EmbeddingHttpClient;
import com.josh.interviewj.llm.provider.OpenAiClientFactory;
import com.josh.interviewj.llm.provider.TemplateAwareEmbeddingExecutor;
import com.josh.interviewj.llm.routing.EmbeddingRouter;
import com.josh.interviewj.llm.template.ClasspathTemplateRegistry;
import com.josh.interviewj.llm.template.TemplateRequestExecutor;
import com.josh.interviewj.llm.template.TemplateResponseExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KbEmbeddingServiceManualTest {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "text-embedding-v4";
    private static final String GITEE_BASE_URL = "https://ai.gitee.com/v1";
    private static final String GITEE_MODEL = "Qwen3-Embedding-8B";
    private static final String NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1";
    private static final String NVIDIA_MODEL = "nvidia/llama-nemotron-embed-1b-v2";
    private static final String LOCAL_ENDPOINT = "http://127.0.0.1:1234/v1/embeddings";
    private static final String LOCAL_MODEL = "text-embedding-qwen3-embedding-0.6b";
    private static final int DEFAULT_DIMENSIONS = 2048;

    /**
     * Verifies the query embedding path returns a 2048-dimension vector.
     */
    @Test
    void embedQuery_RealProviderCall_Returns2048VectorForQueryPath() {
        KbEmbeddingService kbEmbeddingService = createManualService();

        float[] embedding = kbEmbeddingService.embedQuery("Explain Redis persistence in one sentence.");
        logVectorSummary("query", embedding);

        assertValidEmbedding(embedding);
        assertEquals(DEFAULT_DIMENSIONS, embedding.length);
    }

    /**
     * Verifies the document embedding path returns a 2048-dimension vector.
     */
    @Test
    void embedDocument_RealProviderCall_Returns2048VectorForDocumentPath() {
        KbEmbeddingService kbEmbeddingService = createManualService();

        float[] embedding = kbEmbeddingService.embedDocument("Redis persistence supports RDB snapshots and AOF logs.");
        logVectorSummary("document", embedding);

        assertValidEmbedding(embedding);
        assertEquals(DEFAULT_DIMENSIONS, embedding.length);
    }

    /**
     * Verifies query and document inputs produce distinct vectors.
     */
    @Test
    void embedQueryAndDocument_RealProviderCall_ProducesDistinct2048Vectors() {
        KbEmbeddingService kbEmbeddingService = createManualService();

        float[] queryEmbedding = kbEmbeddingService.embedQuery("What is Redis persistence?");
        float[] documentEmbedding = kbEmbeddingService.embedDocument("Redis persistence includes RDB and AOF mechanisms.");

        logVectorSummary("query", queryEmbedding);
        logVectorSummary("document", documentEmbedding);

        assertValidEmbedding(queryEmbedding);
        assertValidEmbedding(documentEmbedding);
        assertEquals(DEFAULT_DIMENSIONS, queryEmbedding.length);
        assertEquals(DEFAULT_DIMENSIONS, documentEmbedding.length);
        assertNotEquals(java.util.Arrays.hashCode(queryEmbedding), java.util.Arrays.hashCode(documentEmbedding));
    }

    /**
     * Verifies the NVIDIA query embedding path returns a 2048-dimension vector.
     */
    @Test
    void embedQuery_NvidiaRealProviderCall_Returns2048VectorForQueryPath() {
        KbEmbeddingService kbEmbeddingService = createNvidiaManualService();

        float[] embedding = kbEmbeddingService.embedQuery("Explain Redis persistence in one sentence.");
        logVectorSummary("nvidia-query", embedding);

        assertValidEmbedding(embedding);
        assertEquals(DEFAULT_DIMENSIONS, embedding.length);
    }

    /**
     * Verifies the NVIDIA document embedding path returns a 2048-dimension vector.
     */
    @Test
    void embedDocument_NvidiaRealProviderCall_Returns2048VectorForDocumentPath() {
        KbEmbeddingService kbEmbeddingService = createNvidiaManualService();

        float[] embedding = kbEmbeddingService.embedDocument("Redis persistence supports RDB snapshots and AOF logs.");
        logVectorSummary("nvidia-document", embedding);

        assertValidEmbedding(embedding);
        assertEquals(DEFAULT_DIMENSIONS, embedding.length);
    }

    /**
     * Verifies NVIDIA query and document inputs produce distinct vectors.
     */
    @Test
    void embedQueryAndDocument_NvidiaRealProviderCall_ProducesDistinct2048Vectors() {
        KbEmbeddingService kbEmbeddingService = createNvidiaManualService();

        float[] queryEmbedding = kbEmbeddingService.embedQuery("What is Redis persistence?");
        float[] documentEmbedding = kbEmbeddingService.embedDocument("Redis persistence includes RDB and AOF mechanisms.");

        logVectorSummary("nvidia-query", queryEmbedding);
        logVectorSummary("nvidia-document", documentEmbedding);

        assertValidEmbedding(queryEmbedding);
        assertValidEmbedding(documentEmbedding);
        assertEquals(DEFAULT_DIMENSIONS, queryEmbedding.length);
        assertEquals(DEFAULT_DIMENSIONS, documentEmbedding.length);
        assertNotEquals(java.util.Arrays.hashCode(queryEmbedding), java.util.Arrays.hashCode(documentEmbedding));
    }

    /**
     * Verifies the Gitee OpenAI-compatible query embedding path returns a 2048-dimension vector.
     */
    @Test
    void embedQuery_GiteeRealProviderCall_Returns2048VectorForQueryPath() {
        KbEmbeddingService kbEmbeddingService = createGiteeManualService();

        float[] embedding = kbEmbeddingService.embedQuery("Explain Redis persistence in one sentence.");
        logVectorSummary("gitee-query", embedding);

        assertValidEmbedding(embedding);
        assertEquals(DEFAULT_DIMENSIONS, embedding.length);
    }

    /**
     * Verifies the local OpenAI-compatible embeddings endpoint is reachable and returns a usable vector.
     */
    @Test
    void localEmbeddingEndpoint_ManualCall_ReturnsUsableVector() throws Exception {
        assumeTrue(isManualRunEnabled(),
                "Set RUN_MANUAL_EMBEDDING_TEST=true or -DrunManualEmbeddingTest=true to execute this test.");

        LocalEmbeddingResponse response = callLocalEmbeddingEndpoint("Some text to embed");
        System.out.println("local embedding status=" + response.statusCode()
                + ", body=" + abbreviate(response.body()));

        assertEquals(200, response.statusCode(),
                "Local embedding endpoint should return HTTP 200. Body: " + abbreviate(response.body()));

        float[] embedding = extractEmbedding(response.body());
        logVectorSummary("local-query", embedding);
        assertValidLocalEmbedding(embedding);
    }

    /**
     * Returns whether the optional manual embedding test flag is enabled.
     *
     * @return {@code true} when manual execution is enabled
     */
    private boolean isManualRunEnabled() {
        String systemProperty = System.getProperty("runManualEmbeddingTest", "false");
        String environmentVariable = System.getenv().getOrDefault("RUN_MANUAL_EMBEDDING_TEST", "false");
        return Boolean.parseBoolean(systemProperty) || Boolean.parseBoolean(environmentVariable);
    }

    /**
     * Creates a manual KB embedding service wired to the SDK-backed adapter.
     *
     * @return manual KB embedding service
     */
    private KbEmbeddingService createManualService() {
        assumeTrue(isManualRunEnabled(),
                "Set RUN_MANUAL_EMBEDDING_TEST=true or -DrunManualEmbeddingTest=true to execute this test.");

        return createManualService(
                "default",
                "ALI_API",
                "app.llm.providers.default",
                DEFAULT_BASE_URL,
                DEFAULT_MODEL,
                Map.of()
        );
    }

    /**
     * Creates a manual KB embedding service wired to the NVIDIA template-based provider path.
     *
     * @return manual KB embedding service
     */
    private KbEmbeddingService createNvidiaManualService() {
        assumeTrue(isManualRunEnabled(),
                "Set RUN_MANUAL_EMBEDDING_TEST=true or -DrunManualEmbeddingTest=true to execute this test.");
        return createManualService(
                "Nvidia",
                "NV_API",
                "app.llm.providers.Nvidia",
                NVIDIA_BASE_URL,
                NVIDIA_MODEL,
                Map.of(
                        "kb_query_embedding", "query",
                        "kb_document_embedding", "passage"
                )
        );
    }

    /**
     * Creates a manual KB embedding service wired to the Gitee OpenAI-compatible provider path.
     *
     * @return manual KB embedding service
     */
    private KbEmbeddingService createGiteeManualService() {
        assumeTrue(isManualRunEnabled(),
                "Set RUN_MANUAL_EMBEDDING_TEST=true or -DrunManualEmbeddingTest=true to execute this test.");

        return createManualService(
                "gitee_ai",
                "GITEE_AI_API",
                "app.llm.providers.gitee_ai",
                GITEE_BASE_URL,
                GITEE_MODEL,
                Map.of()
        );
    }

    /**
     * Creates a manual KB embedding service wired to the requested provider path.
     *
     * @param providerName provider name used by routing
     * @param apiKeyEnvName environment variable containing the API key
     * @param propertyPrefix property prefix for overrides
     * @param defaultBaseUrl provider base URL fallback
     * @param defaultModel provider model fallback
     * @param defaultInputTypes provider-specific embedding input types
     * @return manual KB embedding service
     */
    private KbEmbeddingService createManualService(
            String providerName,
            String apiKeyEnvName,
            String propertyPrefix,
            String defaultBaseUrl,
            String defaultModel,
            Map<String, String> defaultInputTypes
    ) {
        String apiKey = System.getenv(apiKeyEnvName);
        assumeTrue(apiKey != null && !apiKey.isBlank(), "Set " + apiKeyEnvName + " before running this test.");
        String baseUrl = resolveRequiredBaseUrl(propertyPrefix, defaultBaseUrl);

        int configuredDimensions = Integer.parseInt(System.getProperty(
                propertyPrefix + ".embedding.dimension",
                String.valueOf(DEFAULT_DIMENSIONS)
        ));
        assumeTrue(configuredDimensions == DEFAULT_DIMENSIONS,
                "Manual verification is pinned to 2048 dimensions. Current value: " + configuredDimensions);

        LlmProperties properties = new LlmProperties();
        LlmProperties.PurposeRoutingProperties queryRoute = new LlmProperties.PurposeRoutingProperties();
        queryRoute.setStrategy("single");
        queryRoute.setProvider(providerName);
        LlmProperties.PurposeRoutingProperties documentRoute = new LlmProperties.PurposeRoutingProperties();
        documentRoute.setStrategy("single");
        documentRoute.setProvider(providerName);
        properties.getRouting().setPurposes(Map.of(
                "kb_query_embedding", queryRoute,
                "kb_document_embedding", documentRoute
        ));

        LlmProperties.ProviderProperties providerProperties = new LlmProperties.ProviderProperties();
        providerProperties.setBaseUrl(baseUrl);
        providerProperties.setApiKey(apiKey);
        providerProperties.setTimeoutMs(Integer.parseInt(System.getProperty(
                propertyPrefix + ".timeout-ms",
                "30000"
        )));
        providerProperties.setMaxRetries(Integer.parseInt(System.getProperty(
                propertyPrefix + ".max-retries",
                "3"
        )));
        providerProperties.setRetryBackoffMs(Integer.parseInt(System.getProperty(
                propertyPrefix + ".retry-backoff-ms",
                "500"
        )));

        LlmProperties.EmbeddingProperties embeddingProperties = new LlmProperties.EmbeddingProperties();
        embeddingProperties.setDimension(configuredDimensions);
        LlmProperties.ModelProperties models = new LlmProperties.ModelProperties();
        String queryModel = System.getProperty(propertyPrefix + ".embedding.models.kb_query_embedding", defaultModel);
        models.put("kb_query_embedding", queryModel);
        models.put("kb_document_embedding",
                System.getProperty(propertyPrefix + ".embedding.models.kb_document_embedding", queryModel));
        embeddingProperties.setModels(models);
        embeddingProperties.setInputTypes(resolveInputTypes(propertyPrefix, defaultInputTypes));
        providerProperties.setEmbedding(embeddingProperties);
        if ("Nvidia".equals(providerName)) {
            LlmProperties.TemplateProperties templateProperties = new LlmProperties.TemplateProperties();
            templateProperties.setEnabled(Boolean.parseBoolean(System.getProperty(
                    propertyPrefix + ".template.enabled",
                    "true"
            )));
            templateProperties.setStrict(Boolean.parseBoolean(System.getProperty(
                    propertyPrefix + ".template.strict",
                    "true"
            )));
            templateProperties.setRoot(System.getProperty(
                    propertyPrefix + ".template.root",
                    "classpath:/llm-templates/Nvidia"
            ));
            providerProperties.setTemplate(templateProperties);
        }
        properties.setProviders(Map.of(providerName, providerProperties));

        EmbeddingHttpClient sdkClient = new EmbeddingHttpClient(new OpenAiClientFactory());
        tools.jackson.databind.ObjectMapper templateObjectMapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        TemplateAwareEmbeddingExecutor executor = new TemplateAwareEmbeddingExecutor(
                sdkClient,
                new ClasspathTemplateRegistry(new DefaultResourceLoader()),
                new TemplateRequestExecutor(templateObjectMapper),
                new TemplateResponseExtractor(templateObjectMapper)
        );
        EmbeddingRouter embeddingRouter = new EmbeddingRouter((com.josh.interviewj.llm.routing.DatabaseEmbeddingRouteResolver) null);
        return new KbEmbeddingService(new EmbeddingService(embeddingRouter, executor));
    }

    /**
     * Resolves the compatible-mode base URL for manual verification.
     *
     * @param propertyPrefix property prefix for overrides
     * @param defaultBaseUrl provider base URL fallback
     * @return base URL
     */
    private String resolveRequiredBaseUrl(String propertyPrefix, String defaultBaseUrl) {
        String baseUrl = System.getProperty(
                propertyPrefix + ".base-url",
                System.getenv().getOrDefault("LLM_BASE_URL", defaultBaseUrl)
        );
        assumeTrue(baseUrl != null && !baseUrl.isBlank(),
                "Set LLM_BASE_URL or -" + "D" + propertyPrefix + ".base-url before running this test.");
        return baseUrl;
    }

    /**
     * Resolves provider-specific embedding input types for manual verification.
     *
     * @param propertyPrefix property prefix for overrides
     * @param defaultInputTypes provider-specific defaults
     * @return resolved input type mapping
     */
    private Map<String, String> resolveInputTypes(String propertyPrefix, Map<String, String> defaultInputTypes) {
        if (defaultInputTypes.isEmpty()) {
            return Map.of();
        }
        return Map.of(
                "kb_query_embedding",
                System.getProperty(
                        propertyPrefix + ".embedding.input-types.kb_query_embedding",
                        defaultInputTypes.get("kb_query_embedding")
                ),
                "kb_document_embedding",
                System.getProperty(
                        propertyPrefix + ".embedding.input-types.kb_document_embedding",
                        defaultInputTypes.get("kb_document_embedding")
                )
        );
    }

    /**
     * Validates the returned embedding vector.
     *
     * @param embedding embedding vector
     */
    private void assertValidEmbedding(float[] embedding) {
        assertNotNull(embedding);
        assertEquals(DEFAULT_DIMENSIONS, embedding.length);
        assertTrue(allFinite(embedding));
        assertFalse(allZero(embedding));
    }

    /**
     * Logs a short vector summary for manual verification.
     *
     * @param textType logical text type
     * @param embedding embedding vector
     */
    private void logVectorSummary(String textType, float[] embedding) {
        System.out.println(textType + " embedding length=" + embedding.length
                + ", first3=[" + embedding[0] + ", " + embedding[1] + ", " + embedding[2] + "]");
    }

    /**
     * Checks whether every vector value is finite.
     *
     * @param values embedding values
     * @return {@code true} when all values are finite
     */
    private boolean allFinite(float[] values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether every vector value is zero.
     *
     * @param values embedding values
     * @return {@code true} when all values are zero
     */
    private boolean allZero(float[] values) {
        for (float value : values) {
            if (Float.compare(value, 0.0f) != 0) {
                return false;
            }
        }
        return true;
    }

    private LocalEmbeddingResponse callLocalEmbeddingEndpoint(String input) throws Exception {
        tools.jackson.databind.ObjectMapper objectMapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        String endpoint = System.getProperty(
                "manual.local.embedding.endpoint",
                System.getenv().getOrDefault("LOCAL_EMBEDDING_ENDPOINT", LOCAL_ENDPOINT)
        );
        String model = System.getProperty(
                "manual.local.embedding.model",
                System.getenv().getOrDefault("LOCAL_EMBEDDING_MODEL", LOCAL_MODEL)
        );
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "input", input
        ));

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new LocalEmbeddingResponse(response.statusCode(), response.body());
    }

    private float[] extractEmbedding(String responseBody) throws Exception {
        tools.jackson.databind.ObjectMapper objectMapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode vectorNode = root.path("data").path(0).path("embedding");

        assertTrue(vectorNode.isArray() && !vectorNode.isEmpty(),
                "Local embedding response should contain data[0].embedding array. Body: " + abbreviate(responseBody));

        float[] vector = new float[vectorNode.size()];
        for (int i = 0; i < vectorNode.size(); i++) {
            JsonNode value = vectorNode.get(i);
            assertTrue(value.isNumber(),
                    "Local embedding vector should contain only numbers. Body: " + abbreviate(responseBody));
            vector[i] = value.floatValue();
        }
        return vector;
    }

    private void assertValidLocalEmbedding(float[] embedding) {
        assertNotNull(embedding);
        assertTrue(embedding.length > 0, "Local embedding vector should not be empty");
        assertTrue(allFinite(embedding));
        assertFalse(allZero(embedding));
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 237) + "...";
    }

    private record LocalEmbeddingResponse(int statusCode, String body) {
    }
}
