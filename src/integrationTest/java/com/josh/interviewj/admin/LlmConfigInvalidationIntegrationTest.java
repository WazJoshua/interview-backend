package com.josh.interviewj.admin;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.core.LlmRequest;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.provider.TemplateAwareLlmExecutor;
import com.josh.interviewj.llm.support.LlmConfigChangeService;
import com.josh.interviewj.llm.support.LlmConfigCacheService;
import com.josh.interviewj.llm.support.LlmConfigOutboxProcessor;
import com.josh.interviewj.llm.support.LlmConfigVersionPollingService;
import com.josh.interviewj.llm.support.LlmProviderSecretCryptoService;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest
class LlmConfigInvalidationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LLMService llmService;

    @Autowired
    private LlmProviderSecretCryptoService cryptoService;

    @Autowired
    private LlmConfigCacheService llmConfigCacheService;

    @Autowired
    private LlmConfigChangeService llmConfigChangeService;

    @Autowired
    private LlmConfigOutboxProcessor llmConfigOutboxProcessor;

    @Autowired
    private LlmConfigVersionPollingService llmConfigVersionPollingService;

    @MockitoBean
    private TemplateAwareLlmExecutor templateAwareLlmExecutor;

    @BeforeEach
    void setUp() {
        reset(templateAwareLlmExecutor);
        llmConfigCacheService.invalidate();
        jdbcTemplate.execute("DELETE FROM llm_config_change_outbox");
        jdbcTemplate.execute("UPDATE llm_config_version SET current_version = 1 WHERE singleton_key = 'GLOBAL'");
        jdbcTemplate.execute("DELETE FROM llm_routing_policy");
        jdbcTemplate.execute("DELETE FROM llm_provider_secret");
        jdbcTemplate.execute("DELETE FROM llm_model_pricing_version");
        jdbcTemplate.execute("DELETE FROM llm_model_catalog");
        jdbcTemplate.execute("DELETE FROM llm_provider");

        when(templateAwareLlmExecutor.generateText(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new LlmResponse(
                        "{\"status\":\"ok\"}",
                        invocation.getArgument(0),
                        invocation.getArgument(3),
                        new ProviderUsage(UsageFamily.CHAT, 1L, 1L, 1L, 2L, null)
                ));
    }

    @Test
    void outboxPublish_TriggersRuntimeReloadAfterRouteChange() {
        Long providerId = insertProvider("db-provider");
        insertSecret(providerId, "db-api-key");
        Long oldModelId = insertModel(providerId, "db-chat-model-v1");
        insertRouting("analysis", oldModelId);

        assertThat(resolveModel("analysis")).isEqualTo("db-chat-model-v1");

        Long newModelId = insertModel(providerId, "db-chat-model-v2");
        jdbcTemplate.update("UPDATE llm_routing_policy SET model_id = ? WHERE purpose = ?", newModelId, "analysis");
        long nextVersion = llmConfigChangeService.recordChange("ROUTING_UPDATED", "{\"purpose\":\"analysis\"}");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_config_change_outbox WHERE config_version = ?", Integer.class, nextVersion))
                .isEqualTo(1);

        llmConfigOutboxProcessor.publishPendingChanges();

        eventually(() -> assertThat(resolveModel("analysis")).isEqualTo("db-chat-model-v2"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT publish_status FROM llm_config_change_outbox WHERE config_version = ?",
                String.class,
                nextVersion
        )).isEqualTo("PUBLISHED");
    }

    @Test
    void versionPolling_RefreshesRuntimeWhenRedisDeliveryDoesNotHappen() {
        Long providerId = insertProvider("db-provider");
        insertSecret(providerId, "db-api-key");
        Long oldModelId = insertModel(providerId, "db-chat-model-v1");
        insertRouting("analysis", oldModelId);

        assertThat(resolveModel("analysis")).isEqualTo("db-chat-model-v1");

        Long newModelId = insertModel(providerId, "db-chat-model-v2");
        jdbcTemplate.update("UPDATE llm_routing_policy SET model_id = ? WHERE purpose = ?", newModelId, "analysis");
        long nextVersion = llmConfigChangeService.recordChange("ROUTING_UPDATED", "{\"purpose\":\"analysis\"}");

        llmConfigVersionPollingService.refreshIfVersionChanged();

        assertThat(resolveModel("analysis")).isEqualTo("db-chat-model-v2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT publish_status FROM llm_config_change_outbox WHERE config_version = ?",
                String.class,
                nextVersion
        )).isEqualTo("PENDING");
    }

    private String resolveModel(String purpose) {
        return llmService.generateStructuredJson(new LlmRequest(purpose, "sys", "user")).model();
    }

    private Long insertProvider(String providerKey) {
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
                """, Long.class, providerKey, providerKey, "https://db-provider.example.com/v1", 18_000, 2, "[\"CHAT\"]");
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

    private Long insertModel(Long providerId, String modelCode) {
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
                VALUES (?, ?, ?, 'CHAT', ?, TRUE, CAST(? AS jsonb))
                RETURNING id
                """, Long.class, "db-provider", providerId, modelCode, modelCode, "{}");
    }

    private void insertRouting(String purpose, Long modelId) {
        jdbcTemplate.update("""
                INSERT INTO llm_routing_policy (purpose, model_id, enabled, timeout_ms, max_retries)
                VALUES (?, ?, TRUE, 21_000, 1)
                """, purpose, modelId);
    }

    private void eventually(ThrowingAssertion assertion) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        AssertionError lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError error) {
                lastError = error;
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for cache invalidation", interruptedException);
                }
            }
        }
        throw lastError == null ? new AssertionError("Condition was not satisfied before timeout") : lastError;
    }

    @FunctionalInterface
    private interface ThrowingAssertion {
        void run();
    }
}
