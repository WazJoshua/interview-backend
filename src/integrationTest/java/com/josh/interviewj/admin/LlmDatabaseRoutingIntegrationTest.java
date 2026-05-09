package com.josh.interviewj.admin;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.llm.EmbeddingService;
import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.core.EmbeddingRequest;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.LlmRequest;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.ragqa.model.DatabaseRerankConfig;
import com.josh.interviewj.ragqa.service.DatabaseRerankConfigResolver;
import com.josh.interviewj.llm.provider.TemplateAwareEmbeddingExecutor;
import com.josh.interviewj.llm.provider.TemplateAwareLlmExecutor;
import com.josh.interviewj.llm.support.LlmConfigCacheService;
import com.josh.interviewj.llm.support.LlmProviderSecretCryptoService;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class LlmDatabaseRoutingIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LLMService llmService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private LlmProviderSecretCryptoService cryptoService;

    @Autowired
    private LlmConfigCacheService llmConfigCacheService;

    @Autowired
    private DatabaseRerankConfigResolver databaseRerankConfigResolver;

    @MockitoBean
    private TemplateAwareLlmExecutor templateAwareLlmExecutor;

    @MockitoBean
    private TemplateAwareEmbeddingExecutor templateAwareEmbeddingExecutor;

    @BeforeEach
    void setUp() {
        reset(templateAwareLlmExecutor, templateAwareEmbeddingExecutor);
        jdbcTemplate.execute("DELETE FROM llm_routing_policy");
        jdbcTemplate.execute("DELETE FROM llm_provider_secret");
        jdbcTemplate.execute("DELETE FROM llm_model_pricing_version");
        jdbcTemplate.execute("DELETE FROM llm_model_catalog");
        jdbcTemplate.execute("DELETE FROM llm_provider");
        llmConfigCacheService.invalidate();
    }

    @Test
    void databaseRoutes_DriveLlmAndEmbeddingServices() {
        Long providerId = insertProvider("db-provider", "https://db-provider.example.com/v1", 18_000, 2);
        insertSecret(providerId, "db-api-key");
        Long chatModelId = insertModel(providerId, "db-provider", "db-chat-model", "CHAT", null);
        Long embeddingModelId = insertModel(
                providerId,
                "db-provider",
                "db-embedding-model",
                "EMBEDDING",
                "{\"dimension\":1024,\"inputType\":\"query\"}"
        );
        insertRouting("analysis", chatModelId, 21_000, 1);
        insertRouting("kb_query_embedding", embeddingModelId, 17_000, 2);
        llmConfigCacheService.invalidate();

        when(templateAwareLlmExecutor.generateText(any(), any(), any(), any(), any(), any()))
                .thenReturn(new LlmResponse(
                        "{\"status\":\"ok\"}",
                        "db-provider",
                        "db-chat-model",
                        new ProviderUsage(UsageFamily.CHAT, 1L, 3L, 5L, 8L, null)
                ));
        when(templateAwareEmbeddingExecutor.generateEmbedding(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new EmbeddingResponse(
                        new float[]{0.1f, 0.2f},
                        "db-provider",
                        "db-embedding-model",
                        new ProviderUsage(UsageFamily.EMBEDDING, 1L, 2L, null, 2L, null)
                ));

        LlmResponse llmResponse = llmService.generateStructuredJson(new LlmRequest("analysis", "sys", "user"));
        EmbeddingResponse embeddingResponse = embeddingService.generate(new EmbeddingRequest("kb_query_embedding", "hello"));

        assertThat(llmResponse.provider()).isEqualTo("db-provider");
        assertThat(llmResponse.model()).isEqualTo("db-chat-model");
        assertThat(llmResponse.content()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(embeddingResponse.provider()).isEqualTo("db-provider");
        assertThat(embeddingResponse.model()).isEqualTo("db-embedding-model");
        assertThat(embeddingResponse.vector()).containsExactly(0.1f, 0.2f);

        verify(templateAwareLlmExecutor).generateText(any(), any(), any(), any(), any(), any());
        verify(templateAwareEmbeddingExecutor).generateEmbedding(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void missingDatabaseRoute_ThrowsBusinessException() {
        assertThatThrownBy(() -> llmService.generateParseStructuredJson("sys", "user"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("LLM_001");
    }

    @Test
    void databaseRoutes_ExposeRerankRuntimeConfig() {
        Long providerId = insertProvider(
                "rerank-provider",
                "https://rerank.example.com/v1",
                3_000,
                2,
                "[\"RERANK\"]"
        );
        insertSecret(providerId, "rerank-secret");
        Long rerankModelId = insertModel(providerId, "rerank-provider", "qwen-rerank", "RERANK", "{}");
        insertRouting(
                "kb_query_rerank",
                rerankModelId,
                4_000,
                1,
                """
                        {
                          "preRerankCandidateCap": 24,
                          "stage1TopN": 10,
                          "stage1RelevanceThreshold": 0.15,
                          "dualQueryEnabled": true
                        }
                        """
        );
        llmConfigCacheService.invalidate();

        DatabaseRerankConfig config = databaseRerankConfigResolver.resolve("kb_query_rerank").orElseThrow();

        assertThat(config.providerKey()).isEqualTo("rerank-provider");
        assertThat(config.baseUrl()).isEqualTo("https://rerank.example.com/v1");
        assertThat(config.apiKey()).isEqualTo("rerank-secret");
        assertThat(config.model()).isEqualTo("qwen-rerank");
        assertThat(config.timeoutMs()).isEqualTo(4_000);
        assertThat(config.preRerankCandidateCap()).isEqualTo(24);
        assertThat(config.stage1TopN()).isEqualTo(10);
        assertThat(config.stage1RelevanceThreshold()).isEqualTo(0.15D);
        assertThat(config.dualQueryEnabled()).isTrue();
    }

    private Long insertProvider(String providerKey, String baseUrl, int timeoutMs, int maxRetries) {
        return insertProvider(providerKey, baseUrl, timeoutMs, maxRetries, "[\"CHAT\",\"EMBEDDING\"]");
    }

    private Long insertProvider(String providerKey, String baseUrl, int timeoutMs, int maxRetries, String supportedUsageFamilies) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO llm_provider (
                    provider_key,
                    display_name,
                    base_url,
                    enabled,
                    default_timeout_ms,
                    default_max_retries,
                    supported_usage_families
                )
                VALUES (?, ?, ?, TRUE, ?, ?, CAST(? AS jsonb))
                RETURNING id
                """, Long.class, providerKey, providerKey, baseUrl, timeoutMs, maxRetries, supportedUsageFamilies);
    }

    private void insertSecret(Long providerId, String plaintext) {
        LlmProviderSecretCryptoService.EncryptedSecret encrypted = cryptoService.encrypt(plaintext);
        jdbcTemplate.update("""
                INSERT INTO llm_provider_secret (
                    provider_id,
                    api_key_ciphertext,
                    api_key_masked,
                    encryption_key_version,
                    encryption_type
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                providerId,
                encrypted.ciphertext(),
                "db-****ey",
                encrypted.keyVersion(),
                encrypted.encryptionType()
        );
    }

    private Long insertModel(Long providerId, String providerKey, String modelCode, String usageFamily, String metadata) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO llm_model_catalog (
                    provider,
                    provider_id,
                    model_code,
                    usage_family,
                    display_name,
                    active,
                    metadata
                )
                VALUES (?, ?, ?, ?, ?, TRUE, CAST(? AS jsonb))
                RETURNING id
                """, Long.class, providerKey, providerId, modelCode, usageFamily, modelCode, metadata);
    }

    private void insertRouting(String purpose, Long modelId, Integer timeoutMs, Integer maxRetries) {
        insertRouting(purpose, modelId, timeoutMs, maxRetries, "{}");
    }

    private void insertRouting(String purpose, Long modelId, Integer timeoutMs, Integer maxRetries, String metadata) {
        jdbcTemplate.update("""
                INSERT INTO llm_routing_policy (purpose, model_id, enabled, timeout_ms, max_retries, metadata)
                VALUES (?, ?, TRUE, ?, ?, CAST(? AS jsonb))
                """, purpose, modelId, timeoutMs, maxRetries, metadata);
    }
}
