package com.josh.interviewj.llm.routing;

import com.josh.interviewj.config.LlmSecretProperties;
import com.josh.interviewj.llm.support.LlmProviderSecretCryptoService;
import com.josh.interviewj.usage.model.LlmModelCatalog;
import com.josh.interviewj.usage.model.LlmProvider;
import com.josh.interviewj.usage.model.LlmProviderSecret;
import com.josh.interviewj.usage.model.LlmRoutingPolicy;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.repository.LlmProviderSecretRepository;
import com.josh.interviewj.usage.repository.LlmRoutingPolicyRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseLlmRouteSnapshotServiceTest {

    @Test
    void loadSnapshot_BuildsChatAndEmbeddingRoutesFromDatabaseTruth() {
        LlmRoutingPolicyRepository routingPolicyRepository = mock(LlmRoutingPolicyRepository.class);
        LlmProviderSecretRepository secretRepository = mock(LlmProviderSecretRepository.class);
        LlmProviderSecretCryptoService cryptoService = cryptoService();

        LlmProvider provider = LlmProvider.builder()
                .id(1L)
                .providerKey("db-provider")
                .displayName("DB Provider")
                .baseUrl("https://db-provider.example.com/v1")
                .enabled(true)
                .defaultTimeoutMs(12_000)
                .defaultMaxRetries(4)
                .templateRoot("classpath:/llm-templates/Nvidia")
                .build();
        LlmProviderSecret secret = encryptedSecret(provider, cryptoService, "db-secret-key");
        LlmModelCatalog chatModel = LlmModelCatalog.builder()
                .id(10L)
                .provider(provider.getProviderKey())
                .providerRef(provider)
                .modelCode("qwen-chat")
                .usageFamily(UsageFamily.CHAT)
                .active(true)
                .build();
        LlmModelCatalog embeddingModel = LlmModelCatalog.builder()
                .id(11L)
                .provider(provider.getProviderKey())
                .providerRef(provider)
                .modelCode("text-embedding-v4")
                .usageFamily(UsageFamily.EMBEDDING)
                .active(true)
                .metadata("{\"dimension\":1536,\"inputType\":\"query\"}")
                .build();
        LlmRoutingPolicy chatPolicy = LlmRoutingPolicy.builder()
                .purpose("analysis")
                .model(chatModel)
                .enabled(true)
                .timeoutMs(22_000)
                .maxRetries(2)
                .build();
        LlmRoutingPolicy embeddingPolicy = LlmRoutingPolicy.builder()
                .purpose("kb_query_embedding")
                .model(embeddingModel)
                .enabled(true)
                .build();

        when(routingPolicyRepository.findAllWithModelAndProvider()).thenReturn(List.of(chatPolicy, embeddingPolicy));
        when(secretRepository.findByProvider_IdIn(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(secret));

        DatabaseLlmRouteSnapshotService snapshotService = new DatabaseLlmRouteSnapshotService(
                routingPolicyRepository,
                secretRepository,
                cryptoService,
                JsonMapper.builder().build()
        );

        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot snapshot = snapshotService.loadSnapshot();

        assertThat(snapshot.llmRoutes()).containsKey("analysis");
        assertThat(snapshot.llmRoutes().get("analysis").providerName()).isEqualTo("db-provider");
        assertThat(snapshot.llmRoutes().get("analysis").model()).isEqualTo("qwen-chat");
        assertThat(snapshot.llmRoutes().get("analysis").providerConfig().getApiKey()).isEqualTo("db-secret-key");
        assertThat(snapshot.llmRoutes().get("analysis").providerConfig().getTimeoutMs()).isEqualTo(22_000);
        assertThat(snapshot.llmRoutes().get("analysis").providerConfig().getMaxRetries()).isEqualTo(2);

        assertThat(snapshot.embeddingRoutes()).containsKey("kb_query_embedding");
        assertThat(snapshot.embeddingRoutes().get("kb_query_embedding").dimension()).isEqualTo(1536);
        assertThat(snapshot.embeddingRoutes().get("kb_query_embedding").inputType()).isEqualTo("query");
        assertThat(snapshot.invalidPurposes()).isEmpty();
    }

    @Test
    void loadSnapshot_WhenProviderOrModelDisabled_RecordsInvalidPurpose() {
        LlmRoutingPolicyRepository routingPolicyRepository = mock(LlmRoutingPolicyRepository.class);
        LlmProviderSecretRepository secretRepository = mock(LlmProviderSecretRepository.class);
        LlmProviderSecretCryptoService cryptoService = cryptoService();

        LlmProvider disabledProvider = LlmProvider.builder()
                .id(2L)
                .providerKey("disabled-provider")
                .displayName("Disabled Provider")
                .baseUrl("https://disabled.example.com/v1")
                .enabled(false)
                .build();
        LlmModelCatalog disabledModel = LlmModelCatalog.builder()
                .provider("disabled-provider")
                .providerRef(disabledProvider)
                .modelCode("disabled-model")
                .usageFamily(UsageFamily.CHAT)
                .active(false)
                .build();
        LlmRoutingPolicy disabledPolicy = LlmRoutingPolicy.builder()
                .purpose("parse")
                .model(disabledModel)
                .enabled(true)
                .build();

        when(routingPolicyRepository.findAllWithModelAndProvider()).thenReturn(List.of(disabledPolicy));
        when(secretRepository.findByProvider_IdIn(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());

        DatabaseLlmRouteSnapshotService snapshotService = new DatabaseLlmRouteSnapshotService(
                routingPolicyRepository,
                secretRepository,
                cryptoService,
                JsonMapper.builder().build()
        );

        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot snapshot = snapshotService.loadSnapshot();

        assertThat(snapshot.llmRoutes()).isEmpty();
        assertThat(snapshot.invalidPurposes()).containsEntry("parse", "LLM model is disabled for purpose: parse");
    }

    @Test
    void loadSnapshot_WhenRerankCandidateCapExceeds24_RecordsInvalidRerankPurpose() {
        LlmRoutingPolicyRepository routingPolicyRepository = mock(LlmRoutingPolicyRepository.class);
        LlmProviderSecretRepository secretRepository = mock(LlmProviderSecretRepository.class);
        LlmProviderSecretCryptoService cryptoService = cryptoService();

        LlmProvider provider = LlmProvider.builder()
                .id(3L)
                .providerKey("rerank-provider")
                .displayName("Rerank Provider")
                .baseUrl("https://rerank.example.com/v1")
                .enabled(true)
                .defaultTimeoutMs(3_000)
                .defaultMaxRetries(1)
                .build();
        LlmProviderSecret secret = encryptedSecret(provider, cryptoService, "rerank-secret");
        LlmModelCatalog rerankModel = LlmModelCatalog.builder()
                .id(12L)
                .provider("rerank-provider")
                .providerRef(provider)
                .modelCode("qwen-rerank")
                .usageFamily(UsageFamily.RERANK)
                .active(true)
                .build();
        LlmRoutingPolicy rerankPolicy = LlmRoutingPolicy.builder()
                .purpose("kb_query_rerank")
                .model(rerankModel)
                .enabled(true)
                .timeoutMs(4_000)
                .maxRetries(1)
                .metadata("""
                        {"preRerankCandidateCap":25,"stage1TopN":10,"stage1RelevanceThreshold":0.15,"dualQueryEnabled":true}
                        """)
                .build();

        when(routingPolicyRepository.findAllWithModelAndProvider()).thenReturn(List.of(rerankPolicy));
        when(secretRepository.findByProvider_IdIn(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(secret));

        DatabaseLlmRouteSnapshotService snapshotService = new DatabaseLlmRouteSnapshotService(
                routingPolicyRepository,
                secretRepository,
                cryptoService,
                JsonMapper.builder().build()
        );

        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot snapshot = snapshotService.loadSnapshot();

        assertThat(snapshot.rerankConfigs()).isEmpty();
        assertThat(snapshot.invalidRerankPurposes())
                .containsEntry("kb_query_rerank", "Rerank metadata preRerankCandidateCap must be <= 24 for purpose: kb_query_rerank");
    }

    private LlmProviderSecret encryptedSecret(
            LlmProvider provider,
            LlmProviderSecretCryptoService cryptoService,
            String plaintext
    ) {
        LlmProviderSecretCryptoService.EncryptedSecret encrypted = cryptoService.encrypt(plaintext);
        return LlmProviderSecret.builder()
                .provider(provider)
                .apiKeyCiphertext(encrypted.ciphertext())
                .apiKeyMasked("db-****ey")
                .encryptionKeyVersion(encrypted.keyVersion())
                .encryptionType(encrypted.encryptionType())
                .build();
    }

    private LlmProviderSecretCryptoService cryptoService() {
        LlmSecretProperties properties = new LlmSecretProperties();
        properties.setCurrentKeyVersion("current");
        properties.setMasterKeys(Map.of(
                "current", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "previous", "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
        ));
        return new LlmProviderSecretCryptoService(properties);
    }
}
