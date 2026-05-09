package com.josh.interviewj.usage.service;

import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.llm.support.LlmConfigChangeService;
import com.josh.interviewj.llm.support.LlmProviderSecretCryptoService;
import com.josh.interviewj.llm.support.LlmProviderSecretMaskingService;
import com.josh.interviewj.usage.dto.request.AdminModelCatalogQuery;
import com.josh.interviewj.usage.dto.request.AdminPricingVersionQuery;
import com.josh.interviewj.usage.dto.request.CreateModelCatalogRequest;
import com.josh.interviewj.usage.dto.request.CreatePricingVersionRequest;
import com.josh.interviewj.usage.dto.request.CreateProviderRequest;
import com.josh.interviewj.usage.dto.request.UpdateProviderRequest;
import com.josh.interviewj.usage.dto.request.UpdateRoutingPolicyRequest;
import com.josh.interviewj.usage.dto.request.UpdateModelCatalogRequest;
import com.josh.interviewj.usage.dto.response.AdminModelCatalogResponse;
import com.josh.interviewj.usage.dto.response.AdminPricingVersionResponse;
import com.josh.interviewj.usage.dto.response.AdminProviderDetailResponse;
import com.josh.interviewj.usage.dto.response.AdminProviderOptionResponse;
import com.josh.interviewj.usage.dto.response.AdminRoutingPolicyResponse;
import com.josh.interviewj.usage.model.BillingUnit;
import com.josh.interviewj.usage.model.LlmModelCatalog;
import com.josh.interviewj.usage.model.LlmModelPricingVersion;
import com.josh.interviewj.usage.model.LlmProvider;
import com.josh.interviewj.usage.model.LlmProviderSecret;
import com.josh.interviewj.usage.model.LlmRoutingPolicy;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.repository.LlmModelCatalogRepository;
import com.josh.interviewj.usage.repository.LlmModelPricingVersionRepository;
import com.josh.interviewj.usage.repository.LlmProviderRepository;
import com.josh.interviewj.usage.repository.LlmProviderSecretRepository;
import com.josh.interviewj.usage.repository.LlmRoutingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AdminAiResourcesService {

    private static final TypeReference<LinkedHashMap<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final String PURPOSE_KB_QUERY_RERANK = "kb_query_rerank";
    private static final String MODEL_SCOPE_UNIQUE_CONSTRAINT = "uq_llm_model_catalog_provider_model_family";
    private static final String PRICING_SCOPE_FROM_UNIQUE_CONSTRAINT = "uq_llm_model_pricing_version_scope_from";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final LlmModelCatalogRepository llmModelCatalogRepository;
    private final LlmModelPricingVersionRepository llmModelPricingVersionRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderSecretRepository llmProviderSecretRepository;
    private final LlmRoutingPolicyRepository llmRoutingPolicyRepository;
    private final LlmProviderSecretCryptoService cryptoService;
    private final LlmProviderSecretMaskingService maskingService;
    private final LlmConfigChangeService llmConfigChangeService;
    private final AdminOperationLogService adminOperationLogService;

    public List<AdminProviderOptionResponse> getProviders() {
        List<LlmProvider> databaseProviders = llmProviderRepository.findAllByOrderByProviderKeyAsc();
            return databaseProviders.stream()
                    .map(this::toProviderOptionResponse)
                    .toList();
    }

    public AdminProviderDetailResponse getProvider(String id) {
        LlmProvider provider = llmProviderRepository.findById(parseId(id, "provider id"))
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_LLM_002, "Provider not found"));
        return toProviderDetailResponse(provider);
    }

    @Transactional
    public AdminProviderDetailResponse createProvider(Long actorUserId, CreateProviderRequest request) {
        llmProviderRepository.findByProviderKey(request.getProvider())
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.ADMIN_LLM_001, "Provider already exists");
                });

        LlmProvider provider = llmProviderRepository.saveAndFlush(LlmProvider.builder()
                .providerKey(request.getProvider())
                .displayName(request.getDisplayName())
                .baseUrl(request.getBaseUrl())
                .templateRoot(request.getTemplateRoot())
                .enabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled())
                .defaultTimeoutMs(request.getDefaultTimeoutMs())
                .defaultMaxRetries(request.getDefaultMaxRetries())
                .supportedUsageFamilies(serialize(resolveSupportedUsageFamilies(request.getSupportedUsageFamilies())))
                .metadata(serializeMetadata(request.getMetadata()))
                .build());
        upsertProviderSecret(provider, request.getApiKey());
        llmConfigChangeService.recordChange("PROVIDER_CREATED", serialize(Map.of("provider", provider.getProviderKey())));

        AdminProviderDetailResponse response = toProviderDetailResponse(provider);
        adminOperationLogService.recordCreate(
                actorUserId,
                AdminOperationResourceType.LLM_PROVIDER,
                response.getId(),
                null,
                response,
                Map.of("provider", provider.getProviderKey())
        );
        return response;
    }

    @Transactional
    public AdminProviderDetailResponse updateProvider(Long actorUserId, String id, UpdateProviderRequest request) {
        LlmProvider provider = llmProviderRepository.findById(parseId(id, "provider id"))
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_LLM_002, "Provider not found"));
        AdminProviderDetailResponse beforeSnapshot = toProviderDetailResponse(provider);

        provider.setDisplayName(request.getDisplayName());
        provider.setBaseUrl(request.getBaseUrl());
        provider.setTemplateRoot(request.getTemplateRoot());
        provider.setEnabled(request.getEnabled());
        provider.setDefaultTimeoutMs(request.getDefaultTimeoutMs());
        provider.setDefaultMaxRetries(request.getDefaultMaxRetries());
        provider.setSupportedUsageFamilies(serialize(resolveSupportedUsageFamilies(request.getSupportedUsageFamilies())));
        provider.setMetadata(serializeMetadata(request.getMetadata()));
        LlmProvider savedProvider = llmProviderRepository.saveAndFlush(provider);
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            upsertProviderSecret(savedProvider, request.getApiKey());
        }
        llmConfigChangeService.recordChange("PROVIDER_UPDATED", serialize(Map.of("provider", savedProvider.getProviderKey())));

        AdminProviderDetailResponse response = toProviderDetailResponse(savedProvider);
        adminOperationLogService.recordUpdate(
                actorUserId,
                AdminOperationResourceType.LLM_PROVIDER,
                response.getId(),
                null,
                beforeSnapshot,
                response,
                Map.of("provider", savedProvider.getProviderKey())
        );
        return response;
    }

    @Transactional
    public void deleteProvider(Long actorUserId, String id) {
        LlmProvider provider = llmProviderRepository.findById(parseId(id, "provider id"))
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_LLM_002, "Provider not found"));
        AdminProviderDetailResponse beforeSnapshot = toProviderDetailResponse(provider);

        provider.setEnabled(false);
        provider.setDeletedAt(nowUtc());
        llmProviderRepository.saveAndFlush(provider);
        llmConfigChangeService.recordChange("PROVIDER_DELETED", serialize(Map.of("provider", provider.getProviderKey())));
        adminOperationLogService.recordUpdate(
                actorUserId,
                AdminOperationResourceType.LLM_PROVIDER,
                String.valueOf(provider.getId()),
                null,
                beforeSnapshot,
                Map.of("deleted", true),
                Map.of("provider", provider.getProviderKey())
        );
    }

    public Page<AdminModelCatalogResponse> getModels(AdminModelCatalogQuery query) {
        UsageFamily usageFamily = parseManagedUsageFamily(query.getUsageFamily(), true);
        List<AdminModelCatalogResponse> filtered = llmModelCatalogRepository.findAll().stream()
                .filter(model -> matches(query.getProvider(), model.getProvider()))
                .filter(model -> usageFamily == null || usageFamily == model.getUsageFamily())
                .filter(model -> !Boolean.TRUE.equals(query.getActiveOnly()) || Boolean.TRUE.equals(model.getActive()))
                .sorted(Comparator
                        .comparing(LlmModelCatalog::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(LlmModelCatalog::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toModelResponse)
                .toList();
        return toPage(filtered, query.getPage(), query.getSize());
    }

    @Transactional
    public AdminModelCatalogResponse createModel(Long actorUserId, CreateModelCatalogRequest request) {
        UsageFamily usageFamily = parseManagedUsageFamily(request.getUsageFamily(), false);
        validateProviderForUsageFamily(request.getProvider(), usageFamily);
        llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamily(request.getProvider(), request.getModelCode(), usageFamily)
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.ADMIN_LLM_001, "Model scope already exists");
                });

        LlmModelCatalog saved;
        try {
            saved = llmModelCatalogRepository.saveAndFlush(LlmModelCatalog.builder()
                    .provider(request.getProvider())
                    .providerRef(resolveProviderReference(request.getProvider()))
                    .modelCode(request.getModelCode())
                    .usageFamily(usageFamily)
                    .displayName(request.getDisplayName())
                    .active(request.getActive() == null ? Boolean.TRUE : request.getActive())
                    .metadata(serializeMetadata(request.getMetadata()))
                    .build());
        } catch (DataIntegrityViolationException exception) {
            if (isModelScopeConflict(exception, request.getProvider(), request.getModelCode(), usageFamily)) {
                throw new BusinessException(ErrorCode.ADMIN_LLM_001, "Model scope already exists", exception);
            }
            throw exception;
        }
        AdminModelCatalogResponse response = toModelResponse(saved);
        adminOperationLogService.recordCreate(
                actorUserId,
                AdminOperationResourceType.LLM_MODEL_CATALOG,
                response.getId(),
                null,
                response,
                scopeMetadata(saved.getProvider(), saved.getModelCode(), saved.getUsageFamily().name())
        );
        return response;
    }

    @Transactional
    public AdminModelCatalogResponse updateModel(Long actorUserId, String id, UpdateModelCatalogRequest request) {
        LlmModelCatalog catalog = llmModelCatalogRepository.findById(parseId(id, "model id"))
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_LLM_002, "Model catalog entry not found"));

        AdminModelCatalogResponse beforeSnapshot = toModelResponse(catalog);
        catalog.setDisplayName(request.getDisplayName());
        catalog.setActive(request.getActive());
        catalog.setMetadata(serializeMetadata(request.getMetadata()));
        LlmModelCatalog saved = llmModelCatalogRepository.saveAndFlush(catalog);
        AdminModelCatalogResponse response = toModelResponse(saved);
        adminOperationLogService.recordUpdate(
                actorUserId,
                AdminOperationResourceType.LLM_MODEL_CATALOG,
                response.getId(),
                null,
                beforeSnapshot,
                response,
                scopeMetadata(saved.getProvider(), saved.getModelCode(), saved.getUsageFamily().name())
        );
        return response;
    }

    public Page<AdminPricingVersionResponse> getPricingVersions(AdminPricingVersionQuery query) {
        UsageFamily usageFamily = parseManagedUsageFamily(query.getUsageFamily(), true);
        LocalDateTime effectiveAt = nowUtc();
        List<AdminPricingVersionResponse> filtered = llmModelPricingVersionRepository.findAllWithModelAndProvider().stream()
                .filter(version -> matches(query.getProvider(), pricingScope(version).provider()))
                .filter(version -> matches(query.getModelCode(), pricingScope(version).modelCode()))
                .filter(version -> usageFamily == null || usageFamily == version.getUsageFamily())
                .filter(version -> !Boolean.TRUE.equals(query.getCurrentOnly()) || isActive(version, effectiveAt))
                .sorted(Comparator
                        .comparing(LlmModelPricingVersion::getEffectiveFrom, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(LlmModelPricingVersion::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toPricingResponse)
                .toList();
        return toPage(filtered, query.getPage(), query.getSize());
    }

    @Transactional
    public AdminPricingVersionResponse createPricingVersion(Long actorUserId, CreatePricingVersionRequest request) {
        UsageFamily usageFamily = parseManagedUsageFamily(request.getUsageFamily(), false);
        validateProviderForUsageFamily(request.getProvider(), usageFamily);
        validatePricingFields(request);
        llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyForUpdate(request.getProvider(), request.getModelCode(), usageFamily)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_LLM_002, "Model catalog scope not found"));
        LlmModelCatalog pricingModel = llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyWithProvider(
                        request.getProvider(),
                        request.getModelCode(),
                        usageFamily
                )
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_LLM_002, "Model catalog scope not found"));

        LocalDateTime effectiveFrom = toUtcLocalDateTime(request.getEffectiveFrom());
        LocalDateTime effectiveTo = toUtcLocalDateTime(request.getEffectiveTo());
        List<LlmModelPricingVersion> overlaps = pricingModel.getId() == null
                ? llmModelPricingVersionRepository.findOverlappingVersions(
                request.getProvider(),
                request.getModelCode(),
                usageFamily.name(),
                effectiveFrom,
                effectiveTo,
                null
        )
                : llmModelPricingVersionRepository.findOverlappingVersionsByModelId(
                pricingModel.getId(),
                effectiveFrom,
                effectiveTo,
                null
        );
        truncateOverlappingPricingVersions(actorUserId, overlaps, effectiveFrom);

        BillingUnit billingUnit = parseBillingUnit(request.getBillingUnit());
        LlmModelPricingVersion saved;
        try {
            saved = llmModelPricingVersionRepository.saveAndFlush(LlmModelPricingVersion.builder()
                    .provider(request.getProvider())
                    .modelCode(request.getModelCode())
                    .usageFamily(usageFamily)
                    .modelRef(pricingModel)
                    .effectiveFrom(effectiveFrom)
                    .effectiveTo(effectiveTo)
                    .billingUnit(billingUnit)
                    .promptTokenPrice(request.getPromptTokenPrice())
                    .completionTokenPrice(request.getCompletionTokenPrice())
                    .cachedTokenPrice(request.getCachedTokenPrice())
                    .requestPrice(request.getRequestPrice())
                    .currency(request.getCurrency())
                    .metadata(serializeMetadata(request.getMetadata()))
                    .build());
        } catch (DataIntegrityViolationException exception) {
            if (isPricingConflict(exception)) {
                throw new BusinessException(ErrorCode.ADMIN_LLM_001, "Pricing version time range overlaps", exception);
            }
            throw exception;
        }
        AdminPricingVersionResponse response = toPricingResponse(saved);
        adminOperationLogService.recordCreate(
                actorUserId,
                AdminOperationResourceType.LLM_PRICING_VERSION,
                response.getId(),
                null,
                response,
                scopeMetadata(saved.getProvider(), saved.getModelCode(), saved.getUsageFamily().name())
        );
        return response;
    }

    public List<AdminRoutingPolicyResponse> getRoutingPolicies() {
        return llmRoutingPolicyRepository.findAllWithModelAndProvider().stream()
                .map(this::toRoutingPolicyResponse)
                .toList();
    }

    @Transactional
    public AdminRoutingPolicyResponse updateRoutingPolicy(Long actorUserId, String purpose, UpdateRoutingPolicyRequest request) {
        UsageFamily usageFamily = parseManagedUsageFamily(request.getUsageFamily(), false);
        LlmModelCatalog model = llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyWithProvider(
                        request.getProvider(),
                        request.getModelCode(),
                        usageFamily
                )
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_LLM_002, "Model catalog scope not found"));
        validateRoutingPolicyTarget(purpose, usageFamily, model);

        Optional<LlmRoutingPolicy> existing = llmRoutingPolicyRepository.findByPurposeWithModelAndProvider(purpose);
        LlmRoutingPolicy existingPolicy = existing.orElse(null);
        AdminRoutingPolicyResponse beforeSnapshot = existing.map(this::toRoutingPolicyResponse).orElse(null);

        LlmRoutingPolicy policy = existingPolicy != null
                ? existingPolicy
                : LlmRoutingPolicy.builder().purpose(purpose).build();
        policy.setModel(model);
        policy.setEnabled(request.getEnabled());
        policy.setTimeoutMs(request.getTimeoutMs());
        policy.setMaxRetries(request.getMaxRetries());
        policy.setMetadata(resolveRoutingPolicyMetadata(request, existingPolicy));
        LlmRoutingPolicy savedPolicy = llmRoutingPolicyRepository.saveAndFlush(policy);
        llmConfigChangeService.recordChange("ROUTING_UPDATED", serialize(Map.of("purpose", purpose)));

        AdminRoutingPolicyResponse response = toRoutingPolicyResponse(savedPolicy);
        if (beforeSnapshot == null) {
            adminOperationLogService.recordCreate(
                    actorUserId,
                    AdminOperationResourceType.LLM_ROUTING_POLICY,
                    response.getId(),
                    null,
                    response,
                    Map.of("purpose", purpose)
            );
        } else {
            adminOperationLogService.recordUpdate(
                    actorUserId,
                    AdminOperationResourceType.LLM_ROUTING_POLICY,
                    response.getId(),
                    null,
                    beforeSnapshot,
                    response,
                    Map.of("purpose", purpose)
            );
        }
        return response;
    }

    private AdminRoutingPolicyResponse toRoutingPolicyResponse(LlmRoutingPolicy policy) {
        LlmModelCatalog model = policy.getModel();
        LlmProvider provider = model == null ? null : model.getProviderRef();
        String providerKey = provider != null ? provider.getProviderKey() : (model == null ? null : model.getProvider());
        return AdminRoutingPolicyResponse.builder()
                .id(String.valueOf(policy.getId()))
                .purpose(policy.getPurpose())
                .usageFamily(model == null ? null : model.getUsageFamily().name())
                .provider(providerKey)
                .modelCode(model == null ? null : model.getModelCode())
                .enabled(policy.getEnabled())
                .strategy("single")
                .timeoutMs(policy.getTimeoutMs())
                .maxRetries(policy.getMaxRetries())
                .metadata(readMetadata(policy.getMetadata()))
                .fallback(List.of())
                .sourceOfTruth("DATABASE")
                .editable(true)
                .build();
    }

    private String resolveRoutingPolicyMetadata(UpdateRoutingPolicyRequest request, LlmRoutingPolicy existingPolicy) {
        if (request.getMetadata() != null) {
            return serializeMetadata(request.getMetadata());
        }
        return existingPolicy != null && existingPolicy.getMetadata() != null
                ? existingPolicy.getMetadata()
                : serializeMetadata(null);
    }

    private void validateRoutingPolicyTarget(String purpose, UsageFamily usageFamily, LlmModelCatalog model) {
        if (PURPOSE_KB_QUERY_RERANK.equals(purpose) && usageFamily != UsageFamily.RERANK) {
            throw new BusinessException(ErrorCode.ADMIN_LLM_001, "Purpose kb_query_rerank requires usageFamily RERANK");
        }
        if (!Boolean.TRUE.equals(model.getActive())) {
            throw new BusinessException(ErrorCode.ADMIN_LLM_001, "Target model is disabled");
        }
        LlmProvider provider = model.getProviderRef();
        if (provider == null) {
            throw new BusinessException(ErrorCode.ADMIN_LLM_001, "Target provider is missing");
        }
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw new BusinessException(ErrorCode.ADMIN_LLM_001, "Target provider is disabled");
        }
    }

    private void validateProviderForUsageFamily(String provider, UsageFamily usageFamily) {
        LlmProvider providerEntity = llmProviderRepository.findByProviderKey(provider)
                .orElseThrow(() -> new BusinessException("VALIDATION_ERROR", "Unsupported provider"));
        if (!Boolean.TRUE.equals(providerEntity.getEnabled())) {
            throw new BusinessException("VALIDATION_ERROR", "Unsupported provider");
        }
        boolean supported = readSupportedUsageFamilies(providerEntity.getSupportedUsageFamilies())
                .contains(usageFamily.name());
        if (!supported) {
            throw new BusinessException("VALIDATION_ERROR", "Unsupported usageFamily");
        }
    }

    private void validatePricingFields(CreatePricingVersionRequest request) {
        BillingUnit billingUnit = parseBillingUnit(request.getBillingUnit());
        validateNonNegative(request.getPromptTokenPrice(), "promptTokenPrice");
        validateNonNegative(request.getCompletionTokenPrice(), "completionTokenPrice");
        validateNonNegative(request.getCachedTokenPrice(), "cachedTokenPrice");
        validateNonNegative(request.getRequestPrice(), "requestPrice");

        boolean hasTokenPrice = request.getPromptTokenPrice() != null
                || request.getCompletionTokenPrice() != null
                || request.getCachedTokenPrice() != null;
        boolean hasRequestPrice = request.getRequestPrice() != null;

        if (billingUnit == BillingUnit.TOKEN && !hasTokenPrice) {
            throw new BusinessException(ErrorCode.ADMIN_LLM_001, "TOKEN billing requires at least one token price");
        }
        if (billingUnit == BillingUnit.REQUEST && !hasRequestPrice) {
            throw new BusinessException(ErrorCode.ADMIN_LLM_001, "REQUEST billing requires requestPrice");
        }
        if (billingUnit == BillingUnit.TOKEN_AND_REQUEST && (!hasTokenPrice || !hasRequestPrice)) {
            throw new BusinessException(ErrorCode.ADMIN_LLM_001, "TOKEN_AND_REQUEST billing requires both token and request prices");
        }
    }

    private void truncateOverlappingPricingVersions(Long actorUserId, List<LlmModelPricingVersion> overlaps, LocalDateTime effectiveFrom) {
        if (overlaps.isEmpty()) {
            return;
        }

        if (overlaps.size() == 1) {
            LlmModelPricingVersion predecessor = overlaps.getFirst();
            if (predecessor.getEffectiveTo() == null
                    && predecessor.getEffectiveFrom().isBefore(effectiveFrom)
                    && effectiveFrom.isAfter(nowUtc())) {
                AdminPricingVersionResponse beforeSnapshot = toPricingResponse(predecessor);
                predecessor.setEffectiveTo(effectiveFrom);
                LlmModelPricingVersion savedPredecessor = llmModelPricingVersionRepository.saveAndFlush(predecessor);
                adminOperationLogService.recordUpdate(
                        actorUserId,
                        AdminOperationResourceType.LLM_PRICING_VERSION,
                        String.valueOf(savedPredecessor.getId()),
                        null,
                        beforeSnapshot,
                        toPricingResponse(savedPredecessor),
                        successorTruncationMetadata(
                                savedPredecessor.getProvider(),
                                savedPredecessor.getModelCode(),
                                savedPredecessor.getUsageFamily().name(),
                                effectiveFrom
                        )
                );
                return;
            }
        }

        throw new BusinessException(ErrorCode.ADMIN_LLM_001, "Pricing version time range overlaps");
    }

    private AdminModelCatalogResponse toModelResponse(LlmModelCatalog catalog) {
        return AdminModelCatalogResponse.builder()
                .id(String.valueOf(catalog.getId()))
                .provider(catalog.getProvider())
                .modelCode(catalog.getModelCode())
                .usageFamily(catalog.getUsageFamily().name())
                .displayName(catalog.getDisplayName())
                .active(catalog.getActive())
                .metadata(readMetadata(catalog.getMetadata()))
                .createdAt(toOffsetDateTime(catalog.getCreatedAt()))
                .updatedAt(toOffsetDateTime(catalog.getUpdatedAt()))
                .build();
    }

    private AdminProviderOptionResponse toProviderOptionResponse(LlmProvider provider) {
        return AdminProviderOptionResponse.builder()
                .id(String.valueOf(provider.getId()))
                .provider(provider.getProviderKey())
                .displayName(provider.getDisplayName())
                .enabled(provider.getEnabled())
                .supportedUsageFamilies(readSupportedUsageFamilies(provider.getSupportedUsageFamilies()))
                .sourceOfTruth("DATABASE")
                .apiKeyMasked(llmProviderSecretRepository.findByProvider_Id(provider.getId())
                        .map(LlmProviderSecret::getApiKeyMasked)
                        .orElse(null))
                .build();
    }

    private AdminProviderDetailResponse toProviderDetailResponse(LlmProvider provider) {
        return AdminProviderDetailResponse.builder()
                .id(String.valueOf(provider.getId()))
                .provider(provider.getProviderKey())
                .displayName(provider.getDisplayName())
                .baseUrl(provider.getBaseUrl())
                .templateRoot(provider.getTemplateRoot())
                .enabled(provider.getEnabled())
                .defaultTimeoutMs(provider.getDefaultTimeoutMs())
                .defaultMaxRetries(provider.getDefaultMaxRetries())
                .supportedUsageFamilies(readSupportedUsageFamilies(provider.getSupportedUsageFamilies()))
                .metadata(readMetadata(provider.getMetadata()))
                .apiKeyMasked(llmProviderSecretRepository.findByProvider_Id(provider.getId())
                        .map(LlmProviderSecret::getApiKeyMasked)
                        .orElse(null))
                .sourceOfTruth("DATABASE")
                .createdAt(toOffsetDateTime(provider.getCreatedAt()))
                .updatedAt(toOffsetDateTime(provider.getUpdatedAt()))
                .build();
    }

    private AdminPricingVersionResponse toPricingResponse(LlmModelPricingVersion version) {
        PricingScope pricingScope = pricingScope(version);
        return AdminPricingVersionResponse.builder()
                .id(String.valueOf(version.getId()))
                .provider(pricingScope.provider())
                .modelCode(pricingScope.modelCode())
                .usageFamily(version.getUsageFamily().name())
                .effectiveFrom(toOffsetDateTime(version.getEffectiveFrom()))
                .effectiveTo(toOffsetDateTime(version.getEffectiveTo()))
                .billingUnit(version.getBillingUnit().name())
                .promptTokenPrice(formatDecimal(version.getPromptTokenPrice()))
                .completionTokenPrice(formatDecimal(version.getCompletionTokenPrice()))
                .cachedTokenPrice(formatDecimal(version.getCachedTokenPrice()))
                .requestPrice(formatDecimal(version.getRequestPrice()))
                .currency(version.getCurrency())
                .metadata(readMetadata(version.getMetadata()))
                .createdAt(toOffsetDateTime(version.getCreatedAt()))
                .updatedAt(toOffsetDateTime(version.getUpdatedAt()))
                .build();
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        return serialize(metadata == null ? Map.of() : metadata);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize admin AI resources metadata", exception);
        }
    }

    private Map<String, Object> readMetadata(String rawMetadata) {
        if (rawMetadata == null || rawMetadata.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawMetadata, METADATA_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private List<String> readSupportedUsageFamilies(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawValue, STRING_LIST_TYPE);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private BillingUnit parseBillingUnit(String rawValue) {
        return parseEnum(BillingUnit.class, rawValue, "billingUnit");
    }

    private UsageFamily parseManagedUsageFamily(String rawValue, boolean allowNull) {
        if (allowNull && (rawValue == null || rawValue.isBlank())) {
            return null;
        }
        UsageFamily usageFamily = parseEnum(UsageFamily.class, rawValue, "usageFamily");
        if (usageFamily != UsageFamily.CHAT
                && usageFamily != UsageFamily.EMBEDDING
                && usageFamily != UsageFamily.RERANK) {
            throw new BusinessException("VALIDATION_ERROR", "Unsupported usageFamily");
        }
        return usageFamily;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumType, String rawValue, String fieldName) {
        try {
            return Enum.valueOf(enumType, rawValue);
        } catch (RuntimeException exception) {
            throw new BusinessException("VALIDATION_ERROR", "Unsupported " + fieldName);
        }
    }

    private Long parseId(String rawId, String fieldName) {
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException exception) {
            throw new BusinessException("VALIDATION_ERROR", "Invalid " + fieldName);
        }
    }

    private void validateNonNegative(BigDecimal value, String fieldName) {
        if (value != null && value.signum() < 0) {
            throw new BusinessException("VALIDATION_ERROR", fieldName + " must be non-negative");
        }
    }

    private <T> Page<T> toPage(List<T> filtered, Integer page, Integer size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        int fromIndex = Math.min((int) pageRequest.getOffset(), filtered.size());
        int toIndex = Math.min(fromIndex + pageRequest.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(fromIndex, toIndex), pageRequest, filtered.size());
    }

    private List<String> resolveSupportedUsageFamilies(List<String> requestedFamilies) {
        if (requestedFamilies == null || requestedFamilies.isEmpty()) {
            throw new BusinessException("VALIDATION_ERROR", "supportedUsageFamilies is required");
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String requestedFamily : requestedFamilies) {
            UsageFamily usageFamily = parseManagedUsageFamily(requestedFamily, false);
            normalized.add(usageFamily.name());
        }
        return List.copyOf(normalized);
    }

    private LlmProvider resolveProviderReference(String providerKey) {
        return llmProviderRepository.findByProviderKey(providerKey).orElse(null);
    }

    private void upsertProviderSecret(LlmProvider provider, String apiKey) {
        LlmProviderSecret secret = llmProviderSecretRepository.findByProvider_Id(provider.getId())
                .orElseGet(() -> LlmProviderSecret.builder().provider(provider).build());
        LlmProviderSecretCryptoService.EncryptedSecret encryptedSecret = cryptoService.encrypt(apiKey);
        secret.setProvider(provider);
        secret.setApiKeyCiphertext(encryptedSecret.ciphertext());
        secret.setApiKeyMasked(maskingService.mask(apiKey));
        secret.setEncryptionKeyVersion(encryptedSecret.keyVersion());
        secret.setEncryptionType(encryptedSecret.encryptionType());
        llmProviderSecretRepository.save(secret);
    }

    private Map<String, Object> scopeMetadata(String provider, String modelCode, String usageFamily) {
        return Map.of(
                "provider", provider,
                "modelCode", modelCode,
                "usageFamily", usageFamily
        );
    }

    private Map<String, Object> successorTruncationMetadata(
            String provider,
            String modelCode,
            String usageFamily,
            LocalDateTime successorEffectiveFrom
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(scopeMetadata(provider, modelCode, usageFamily));
        metadata.put("changeType", "TRUNCATED_BY_SUCCESSOR_CREATE");
        metadata.put("successorEffectiveFrom", successorEffectiveFrom.toString());
        return metadata;
    }

    private String formatDecimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private PricingScope pricingScope(LlmModelPricingVersion version) {
        LlmModelCatalog model = version.getModelRef();
        if (model == null) {
            return new PricingScope(version.getProvider(), version.getModelCode());
        }
        String provider = model.getProviderRef() == null
                ? model.getProvider()
                : model.getProviderRef().getProviderKey();
        return new PricingScope(provider, model.getModelCode());
    }

    private boolean isActive(LlmModelPricingVersion version, LocalDateTime effectiveAt) {
        return !version.getEffectiveFrom().isAfter(effectiveAt)
                && (version.getEffectiveTo() == null || version.getEffectiveTo().isAfter(effectiveAt));
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private LocalDateTime toUtcLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private boolean isModelScopeConflict(
            DataIntegrityViolationException exception,
            String provider,
            String modelCode,
            UsageFamily usageFamily
    ) {
        if (MODEL_SCOPE_UNIQUE_CONSTRAINT.equals(findConstraintName(exception))) {
            return true;
        }
        return llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamily(provider, modelCode, usageFamily).isPresent();
    }

    private boolean isPricingConflict(DataIntegrityViolationException exception) {
        String constraintName = findConstraintName(exception);
        return PRICING_SCOPE_FROM_UNIQUE_CONSTRAINT.equals(constraintName);
    }

    private String findConstraintName(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolationException) {
                return constraintViolationException.getConstraintName();
            }
            if (current instanceof SQLException sqlException && sqlException.getCause() != null) {
                current = sqlException.getCause();
                continue;
            }
            current = current.getCause();
        }
        return null;
    }

    private record PricingScope(String provider, String modelCode) {
    }
}
