package com.josh.interviewj.usage.service;

import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.config.LlmSecretProperties;
import com.josh.interviewj.llm.support.LlmConfigChangeService;
import com.josh.interviewj.llm.support.LlmProviderSecretCryptoService;
import com.josh.interviewj.llm.support.LlmProviderSecretMaskingService;
import com.josh.interviewj.usage.dto.request.CreateModelCatalogRequest;
import com.josh.interviewj.usage.dto.request.AdminPricingVersionQuery;
import com.josh.interviewj.usage.dto.request.CreatePricingVersionRequest;
import com.josh.interviewj.usage.dto.request.UpdateRoutingPolicyRequest;
import com.josh.interviewj.usage.dto.response.AdminProviderOptionResponse;
import com.josh.interviewj.usage.dto.response.AdminRoutingPolicyResponse;
import com.josh.interviewj.usage.model.BillingUnit;
import com.josh.interviewj.usage.model.LlmModelCatalog;
import com.josh.interviewj.usage.model.LlmModelPricingVersion;
import com.josh.interviewj.usage.model.LlmProvider;
import com.josh.interviewj.usage.model.LlmRoutingPolicy;
import com.josh.interviewj.usage.model.LlmProviderSecret;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.repository.LlmModelCatalogRepository;
import com.josh.interviewj.usage.repository.LlmModelPricingVersionRepository;
import com.josh.interviewj.usage.repository.LlmProviderRepository;
import com.josh.interviewj.usage.repository.LlmProviderSecretRepository;
import com.josh.interviewj.usage.repository.LlmRoutingPolicyRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAiResourcesServiceTest {

    @Mock
    private LlmModelCatalogRepository llmModelCatalogRepository;

    @Mock
    private LlmModelPricingVersionRepository llmModelPricingVersionRepository;

    @Mock
    private AdminOperationLogService adminOperationLogService;

    @Mock
    private LlmProviderRepository llmProviderRepository;

    @Mock
    private LlmProviderSecretRepository llmProviderSecretRepository;

    @Mock
    private LlmRoutingPolicyRepository llmRoutingPolicyRepository;

    @Mock
    private LlmConfigChangeService llmConfigChangeService;

    private AdminAiResourcesService service;

    @BeforeEach
    void setUp() {
        LlmSecretProperties secretProperties = new LlmSecretProperties();
        secretProperties.setCurrentKeyVersion("current");
        secretProperties.setMasterKeys(Map.of(
                "current", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "previous", "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
        ));
        service = new AdminAiResourcesService(
                Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC),
                JsonMapper.builder().build(),
                llmModelCatalogRepository,
                llmModelPricingVersionRepository,
                llmProviderRepository,
                llmProviderSecretRepository,
                llmRoutingPolicyRepository,
                new LlmProviderSecretCryptoService(secretProperties),
                new LlmProviderSecretMaskingService(),
                llmConfigChangeService,
                adminOperationLogService
        );
        lenient().when(llmProviderRepository.findAllByOrderByProviderKeyAsc()).thenReturn(List.of());
        lenient().when(llmProviderRepository.findByProviderKey("dispatcher_rc")).thenReturn(Optional.of(
                LlmProvider.builder()
                        .id(31L)
                        .providerKey("dispatcher_rc")
                        .enabled(true)
                        .supportedUsageFamilies("[\"CHAT\"]")
                        .build()
        ));
        lenient().when(llmRoutingPolicyRepository.findAllWithModelAndProvider()).thenReturn(List.of());
    }

    @Test
    void getProviders_WhenDatabaseTruthExists_ReturnsDatabaseOptions() {
        LlmProvider provider = LlmProvider.builder()
                .id(11L)
                .providerKey("db-provider")
                .displayName("DB Provider")
                .enabled(true)
                .supportedUsageFamilies("[\"CHAT\",\"EMBEDDING\"]")
                .build();
        when(llmProviderRepository.findAllByOrderByProviderKeyAsc()).thenReturn(List.of(provider));
        when(llmProviderSecretRepository.findByProvider_Id(11L))
                .thenReturn(Optional.of(LlmProviderSecret.builder().apiKeyMasked("db-****ey").build()));

        List<AdminProviderOptionResponse> responses = service.getProviders();

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().getProvider()).isEqualTo("db-provider");
        assertThat(responses.getFirst().getSourceOfTruth()).isEqualTo("DATABASE");
        assertThat(responses.getFirst().getApiKeyMasked()).isEqualTo("db-****ey");
    }

    @Test
    void createModel_WhenProviderUnsupported_ThrowsValidationError() {
        CreateModelCatalogRequest request = new CreateModelCatalogRequest();
        request.setProvider("unknown_provider");
        request.setModelCode("gpt-5.4");
        request.setUsageFamily("CHAT");

        assertThatThrownBy(() -> service.createModel(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createModel_WhenScopeDuplicate_ThrowsAdminLlm001() {
        CreateModelCatalogRequest request = new CreateModelCatalogRequest();
        request.setProvider("dispatcher_rc");
        request.setModelCode("gpt-5.4");
        request.setUsageFamily("CHAT");
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamily("dispatcher_rc", "gpt-5.4", UsageFamily.CHAT))
                .thenReturn(Optional.of(LlmModelCatalog.builder().id(1L).build()));

        assertThatThrownBy(() -> service.createModel(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_LLM_001);
    }

    @Test
    void createModel_WhenUniqueConstraintHit_ThrowsAdminLlm001() {
        CreateModelCatalogRequest request = new CreateModelCatalogRequest();
        request.setProvider("dispatcher_rc");
        request.setModelCode("gpt-5.4");
        request.setUsageFamily("CHAT");
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamily("dispatcher_rc", "gpt-5.4", UsageFamily.CHAT))
                .thenReturn(Optional.empty());
        when(llmModelCatalogRepository.saveAndFlush(any(LlmModelCatalog.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate",
                        new ConstraintViolationException(
                                "duplicate",
                                new SQLException("duplicate"),
                                "uq_llm_model_catalog_provider_model_family"
                        )
                ));

        assertThatThrownBy(() -> service.createModel(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_LLM_001);
    }

    @Test
    void createModel_PersistsAndWritesAuditLog() {
        CreateModelCatalogRequest request = new CreateModelCatalogRequest();
        request.setProvider("dispatcher_rc");
        request.setModelCode("gpt-5.4");
        request.setUsageFamily("CHAT");
        request.setDisplayName("GPT-5.4");
        request.setMetadata(Map.of("tier", "default"));
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamily("dispatcher_rc", "gpt-5.4", UsageFamily.CHAT))
                .thenReturn(Optional.empty());
        when(llmModelCatalogRepository.saveAndFlush(any(LlmModelCatalog.class)))
                .thenAnswer(invocation -> {
                    LlmModelCatalog catalog = invocation.getArgument(0);
                    catalog.setId(8L);
                    catalog.setCreatedAt(LocalDateTime.of(2026, 4, 1, 0, 0));
                    catalog.setUpdatedAt(LocalDateTime.of(2026, 4, 1, 0, 0));
                    return catalog;
                });

        var response = service.createModel(101L, request);

        ArgumentCaptor<LlmModelCatalog> captor = ArgumentCaptor.forClass(LlmModelCatalog.class);
        verify(llmModelCatalogRepository).saveAndFlush(captor.capture());
        LlmModelCatalog saved = captor.getValue();
        assertThat(saved.getActive()).isTrue();
        assertThat(saved.getMetadata()).isEqualTo("{\"tier\":\"default\"}");
        assertThat(response.getId()).isEqualTo("8");
        verify(adminOperationLogService).recordCreate(
                eq(101L),
                eq(AdminOperationResourceType.LLM_MODEL_CATALOG),
                eq("8"),
                eq(null),
                any(),
                eq(Map.of("provider", "dispatcher_rc", "modelCode", "gpt-5.4", "usageFamily", "CHAT"))
        );
    }

    @Test
    void createPricingVersion_WhenModelScopeMissing_ThrowsAdminLlm002() {
        CreatePricingVersionRequest request = validPricingRequest();
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyForUpdate("dispatcher_rc", "gpt-5.4", UsageFamily.CHAT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPricingVersion(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_LLM_002);
    }

    @Test
    void createPricingVersion_WhenOnlyFutureOpenEndedPredecessorOverlaps_TruncatesAndCreatesVersion() {
        CreatePricingVersionRequest request = validPricingRequest();
        request.setEffectiveFrom(OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyForUpdate("dispatcher_rc", "gpt-5.4", UsageFamily.CHAT))
                .thenReturn(Optional.of(LlmModelCatalog.builder().id(1L).build()));
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyWithProvider("dispatcher_rc", "gpt-5.4", UsageFamily.CHAT))
                .thenReturn(Optional.of(LlmModelCatalog.builder().id(1L).provider("dispatcher_rc").modelCode("gpt-5.4").usageFamily(UsageFamily.CHAT).build()));

        LlmModelPricingVersion existing = LlmModelPricingVersion.builder()
                .id(9L)
                .provider("dispatcher_rc")
                .modelCode("gpt-5.4")
                .usageFamily(UsageFamily.CHAT)
                .effectiveFrom(LocalDateTime.of(2026, 3, 1, 0, 0))
                .effectiveTo(null)
                .billingUnit(BillingUnit.TOKEN)
                .promptTokenPrice(new BigDecimal("0.000009"))
                .currency("USD")
                .build();
        when(llmModelPricingVersionRepository.findOverlappingVersionsByModelId(eq(1L), any(), any(), any()))
                .thenReturn(List.of(existing));
        when(llmModelPricingVersionRepository.saveAndFlush(any(LlmModelPricingVersion.class)))
                .thenAnswer(invocation -> {
                    LlmModelPricingVersion version = invocation.getArgument(0);
                    if (version.getId() == null) {
                        version.setId(10L);
                        version.setCreatedAt(LocalDateTime.of(2026, 4, 1, 0, 0));
                        version.setUpdatedAt(LocalDateTime.of(2026, 4, 1, 0, 0));
                    }
                    return version;
                });

        var response = service.createPricingVersion(102L, request);

        ArgumentCaptor<LlmModelPricingVersion> captor = ArgumentCaptor.forClass(LlmModelPricingVersion.class);
        verify(llmModelPricingVersionRepository, times(2)).saveAndFlush(captor.capture());
        List<LlmModelPricingVersion> savedVersions = captor.getAllValues();
        assertThat(savedVersions.get(0).getId()).isEqualTo(9L);
        assertThat(savedVersions.get(0).getEffectiveTo()).isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0));
        assertThat(savedVersions.get(1).getId()).isEqualTo(10L);
        assertThat(response.getId()).isEqualTo("10");
        verify(adminOperationLogService).recordUpdate(
                eq(102L),
                eq(AdminOperationResourceType.LLM_PRICING_VERSION),
                eq("9"),
                eq(null),
                any(),
                any(),
                eq(Map.of(
                        "provider", "dispatcher_rc",
                        "modelCode", "gpt-5.4",
                        "usageFamily", "CHAT",
                        "changeType", "TRUNCATED_BY_SUCCESSOR_CREATE",
                        "successorEffectiveFrom", "2026-05-01T00:00"
                ))
        );
        verify(adminOperationLogService).recordCreate(
                eq(102L),
                eq(AdminOperationResourceType.LLM_PRICING_VERSION),
                eq("10"),
                eq(null),
                any(),
                eq(Map.of("provider", "dispatcher_rc", "modelCode", "gpt-5.4", "usageFamily", "CHAT"))
        );
    }

    @Test
    void createPricingVersion_WhenUniqueConstraintHit_ThrowsAdminLlm001() {
        CreatePricingVersionRequest request = validPricingRequest();
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyForUpdate("dispatcher_rc", "gpt-5.4", UsageFamily.CHAT))
                .thenReturn(Optional.of(LlmModelCatalog.builder().id(1L).build()));
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyWithProvider("dispatcher_rc", "gpt-5.4", UsageFamily.CHAT))
                .thenReturn(Optional.of(LlmModelCatalog.builder().id(1L).provider("dispatcher_rc").modelCode("gpt-5.4").usageFamily(UsageFamily.CHAT).build()));
        when(llmModelPricingVersionRepository.findOverlappingVersionsByModelId(eq(1L), any(), any(), any()))
                .thenReturn(List.of());
        when(llmModelPricingVersionRepository.saveAndFlush(any(LlmModelPricingVersion.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate",
                        new ConstraintViolationException(
                                "duplicate",
                                new SQLException("duplicate"),
                                "uq_llm_model_pricing_version_scope_from"
                        )
                ));

        assertThatThrownBy(() -> service.createPricingVersion(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_LLM_001);
    }

    @Test
    void getPricingVersions_WhenLegacyScopeDiffers_PrefersCanonicalModelReferenceScope() {
        LlmProvider canonicalProvider = LlmProvider.builder()
                .id(31L)
                .providerKey("dispatcher_rc")
                .build();
        LlmModelCatalog canonicalModel = LlmModelCatalog.builder()
                .id(21L)
                .provider("legacy-provider")
                .providerRef(canonicalProvider)
                .modelCode("gpt-5.4")
                .usageFamily(UsageFamily.CHAT)
                .build();
        when(llmModelPricingVersionRepository.findAllWithModelAndProvider()).thenReturn(List.of(
                LlmModelPricingVersion.builder()
                        .id(41L)
                        .provider("legacy-provider")
                        .modelCode("legacy-model")
                        .usageFamily(UsageFamily.CHAT)
                        .modelRef(canonicalModel)
                        .effectiveFrom(LocalDateTime.of(2026, 4, 1, 0, 0))
                        .billingUnit(BillingUnit.TOKEN)
                        .promptTokenPrice(new BigDecimal("0.000010"))
                        .currency("USD")
                        .build()
        ));

        AdminPricingVersionQuery query = new AdminPricingVersionQuery();
        query.setProvider("dispatcher_rc");
        query.setModelCode("gpt-5.4");

        var response = service.getPricingVersions(query);

        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getContent().getFirst().getProvider()).isEqualTo("dispatcher_rc");
        assertThat(response.getContent().getFirst().getModelCode()).isEqualTo("gpt-5.4");
    }

    @Test
    void getRoutingPolicies_WhenDatabaseEmpty_ReturnsEmptyList() {
        List<AdminRoutingPolicyResponse> responses = service.getRoutingPolicies();

        assertThat(responses).isEmpty();
    }

    @Test
    void updateRoutingPolicy_CreatesDatabasePolicyAndRecordsChange() {
        UpdateRoutingPolicyRequest request = new UpdateRoutingPolicyRequest();
        request.setProvider("dispatcher_rc");
        request.setModelCode("gpt-5.4");
        request.setUsageFamily("CHAT");
        request.setEnabled(true);
        request.setTimeoutMs(1800);
        request.setMaxRetries(2);

        LlmModelCatalog model = LlmModelCatalog.builder()
                .id(21L)
                .provider("dispatcher_rc")
                .modelCode("gpt-5.4")
                .usageFamily(UsageFamily.CHAT)
                .active(true)
                .providerRef(LlmProvider.builder().id(31L).providerKey("dispatcher_rc").enabled(true).build())
                .build();
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyWithProvider("dispatcher_rc", "gpt-5.4", UsageFamily.CHAT))
                .thenReturn(Optional.of(model));
        when(llmRoutingPolicyRepository.findByPurposeWithModelAndProvider("analysis")).thenReturn(Optional.empty());
        when(llmRoutingPolicyRepository.saveAndFlush(any(LlmRoutingPolicy.class)))
                .thenAnswer(invocation -> {
                    LlmRoutingPolicy policy = invocation.getArgument(0);
                    policy.setId(41L);
                    return policy;
                });

        AdminRoutingPolicyResponse response = service.updateRoutingPolicy(101L, "analysis", request);

        assertThat(response.getSourceOfTruth()).isEqualTo("DATABASE");
        assertThat(response.getEditable()).isTrue();
        assertThat(response.getProvider()).isEqualTo("dispatcher_rc");
        assertThat(response.getModelCode()).isEqualTo("gpt-5.4");
        verify(llmConfigChangeService).recordChange("ROUTING_UPDATED", "{\"purpose\":\"analysis\"}");
        verify(adminOperationLogService).recordCreate(
                eq(101L),
                eq(AdminOperationResourceType.LLM_ROUTING_POLICY),
                eq("41"),
                eq(null),
                any(),
                eq(Map.of("purpose", "analysis"))
        );
    }

    @Test
    void updateRoutingPolicy_RerankPersistsMetadataAndReturnsIt() {
        UpdateRoutingPolicyRequest request = new UpdateRoutingPolicyRequest();
        request.setProvider("rerank_provider");
        request.setModelCode("qwen-rerank");
        request.setUsageFamily("RERANK");
        request.setEnabled(true);
        request.setTimeoutMs(3_000);
        request.setMaxRetries(1);
        request.setMetadata(Map.of(
                "preRerankCandidateCap", 24,
                "stage1TopN", 10,
                "stage1RelevanceThreshold", 0.15D,
                "dualQueryEnabled", true
        ));

        LlmProvider provider = LlmProvider.builder()
                .id(77L)
                .providerKey("rerank_provider")
                .enabled(true)
                .supportedUsageFamilies("[\"RERANK\"]")
                .build();
        LlmModelCatalog model = LlmModelCatalog.builder()
                .id(78L)
                .provider("rerank_provider")
                .modelCode("qwen-rerank")
                .usageFamily(UsageFamily.RERANK)
                .active(true)
                .providerRef(provider)
                .build();
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyWithProvider("rerank_provider", "qwen-rerank", UsageFamily.RERANK))
                .thenReturn(Optional.of(model));
        when(llmRoutingPolicyRepository.findByPurposeWithModelAndProvider("kb_query_rerank")).thenReturn(Optional.empty());
        when(llmRoutingPolicyRepository.saveAndFlush(any(LlmRoutingPolicy.class)))
                .thenAnswer(invocation -> {
                    LlmRoutingPolicy policy = invocation.getArgument(0);
                    policy.setId(88L);
                    return policy;
                });

        AdminRoutingPolicyResponse response = service.updateRoutingPolicy(101L, "kb_query_rerank", request);

        ArgumentCaptor<LlmRoutingPolicy> captor = ArgumentCaptor.forClass(LlmRoutingPolicy.class);
        verify(llmRoutingPolicyRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMetadata()).contains("\"preRerankCandidateCap\":24");
        assertThat(captor.getValue().getMetadata()).contains("\"stage1TopN\":10");
        assertThat(captor.getValue().getMetadata()).contains("\"stage1RelevanceThreshold\":0.15");
        assertThat(captor.getValue().getMetadata()).contains("\"dualQueryEnabled\":true");
        assertThat(response.getUsageFamily()).isEqualTo("RERANK");
        assertThat(response.getProvider()).isEqualTo("rerank_provider");
        assertThat(response.getMetadata()).containsEntry("preRerankCandidateCap", 24);
        assertThat(response.getMetadata()).containsEntry("dualQueryEnabled", true);
    }

    @Test
    void updateRoutingPolicy_WhenMetadataOmitted_PreservesExistingMetadata() {
        UpdateRoutingPolicyRequest request = new UpdateRoutingPolicyRequest();
        request.setProvider("rerank_provider");
        request.setModelCode("qwen-rerank");
        request.setUsageFamily("RERANK");
        request.setEnabled(true);
        request.setTimeoutMs(3_500);
        request.setMaxRetries(2);

        LlmProvider provider = LlmProvider.builder()
                .id(77L)
                .providerKey("rerank_provider")
                .enabled(true)
                .supportedUsageFamilies("[\"RERANK\"]")
                .build();
        LlmModelCatalog model = LlmModelCatalog.builder()
                .id(78L)
                .provider("rerank_provider")
                .modelCode("qwen-rerank")
                .usageFamily(UsageFamily.RERANK)
                .active(true)
                .providerRef(provider)
                .build();
        LlmRoutingPolicy existingPolicy = LlmRoutingPolicy.builder()
                .id(88L)
                .purpose("kb_query_rerank")
                .model(model)
                .enabled(true)
                .timeoutMs(3_000)
                .maxRetries(1)
                .metadata("""
                        {"preRerankCandidateCap":24,"stage1TopN":10,"stage1RelevanceThreshold":0.15,"dualQueryEnabled":true}
                        """)
                .build();
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyWithProvider("rerank_provider", "qwen-rerank", UsageFamily.RERANK))
                .thenReturn(Optional.of(model));
        when(llmRoutingPolicyRepository.findByPurposeWithModelAndProvider("kb_query_rerank"))
                .thenReturn(Optional.of(existingPolicy));
        when(llmRoutingPolicyRepository.saveAndFlush(any(LlmRoutingPolicy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AdminRoutingPolicyResponse response = service.updateRoutingPolicy(101L, "kb_query_rerank", request);

        ArgumentCaptor<LlmRoutingPolicy> captor = ArgumentCaptor.forClass(LlmRoutingPolicy.class);
        verify(llmRoutingPolicyRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTimeoutMs()).isEqualTo(3_500);
        assertThat(captor.getValue().getMetadata())
                .isEqualTo(existingPolicy.getMetadata());
        assertThat(response.getMetadata()).containsEntry("preRerankCandidateCap", 24);
        assertThat(response.getMetadata()).containsEntry("stage1TopN", 10);
        assertThat(response.getMetadata()).containsEntry("dualQueryEnabled", true);
    }

    @Test
    void createPricingVersion_WhenReplacingCurrentWindow_ThrowsAdminLlm001() {
        CreatePricingVersionRequest request = validPricingRequest();
        request.setEffectiveFrom(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyForUpdate("dispatcher_rc", "gpt-5.4", UsageFamily.CHAT))
                .thenReturn(Optional.of(LlmModelCatalog.builder().id(1L).build()));
        when(llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyWithProvider("dispatcher_rc", "gpt-5.4", UsageFamily.CHAT))
                .thenReturn(Optional.of(LlmModelCatalog.builder().id(1L).provider("dispatcher_rc").modelCode("gpt-5.4").usageFamily(UsageFamily.CHAT).build()));
        when(llmModelPricingVersionRepository.findOverlappingVersionsByModelId(eq(1L), any(), any(), any()))
                .thenReturn(List.of(LlmModelPricingVersion.builder()
                        .id(9L)
                        .provider("dispatcher_rc")
                        .modelCode("gpt-5.4")
                        .usageFamily(UsageFamily.CHAT)
                        .effectiveFrom(LocalDateTime.of(2026, 3, 1, 0, 0))
                        .effectiveTo(null)
                        .billingUnit(BillingUnit.TOKEN)
                        .promptTokenPrice(new BigDecimal("0.000009"))
                        .currency("USD")
                        .build()));

        assertThatThrownBy(() -> service.createPricingVersion(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_LLM_001);
        verify(llmModelPricingVersionRepository, never()).saveAndFlush(any(LlmModelPricingVersion.class));
    }

    private CreatePricingVersionRequest validPricingRequest() {
        CreatePricingVersionRequest request = new CreatePricingVersionRequest();
        request.setProvider("dispatcher_rc");
        request.setModelCode("gpt-5.4");
        request.setUsageFamily("CHAT");
        request.setEffectiveFrom(OffsetDateTime.of(2026, 4, 15, 0, 0, 0, 0, ZoneOffset.UTC));
        request.setBillingUnit("TOKEN");
        request.setPromptTokenPrice(new BigDecimal("0.000010"));
        request.setCurrency("USD");
        request.setMetadata(Map.of("tier", "default"));
        return request;
    }

}
