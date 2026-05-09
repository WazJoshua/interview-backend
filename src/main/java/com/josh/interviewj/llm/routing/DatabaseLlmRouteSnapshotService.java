package com.josh.interviewj.llm.routing;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.support.LlmProviderSecretCryptoService;
import com.josh.interviewj.ragqa.model.DatabaseRerankConfig;
import com.josh.interviewj.usage.model.LlmModelCatalog;
import com.josh.interviewj.usage.model.LlmProvider;
import com.josh.interviewj.usage.model.LlmProviderSecret;
import com.josh.interviewj.usage.model.LlmRoutingPolicy;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.repository.LlmProviderSecretRepository;
import com.josh.interviewj.usage.repository.LlmRoutingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DatabaseLlmRouteSnapshotService {

    private static final int MAX_PRE_RERANK_CANDIDATE_CAP = 24;

    private final LlmRoutingPolicyRepository llmRoutingPolicyRepository;
    private final LlmProviderSecretRepository llmProviderSecretRepository;
    private final LlmProviderSecretCryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public DatabaseRouteSnapshot loadSnapshot() {
        List<LlmRoutingPolicy> policies = llmRoutingPolicyRepository.findAllWithModelAndProvider();
        Collection<Long> providerIds = policies.stream()
                .map(LlmRoutingPolicy::getModel)
                .filter(Objects::nonNull)
                .map(LlmModelCatalog::getProviderRef)
                .filter(Objects::nonNull)
                .map(LlmProvider::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, LlmProviderSecret> secretsByProviderId = llmProviderSecretRepository.findByProvider_IdIn(providerIds)
                .stream()
                .collect(Collectors.toMap(secret -> secret.getProvider().getId(), Function.identity()));

        Map<String, LlmRoute> llmRoutes = new LinkedHashMap<>();
        Map<String, EmbeddingRoute> embeddingRoutes = new LinkedHashMap<>();
        Map<String, String> invalidPurposes = new LinkedHashMap<>();
        Map<String, DatabaseRerankConfig> rerankConfigs = new LinkedHashMap<>();
        Map<String, String> invalidRerankPurposes = new LinkedHashMap<>();

        for (LlmRoutingPolicy policy : policies) {
            if (isDisabledRerankPolicy(policy)) {
                continue;
            }
            String validationError = validate(policy, secretsByProviderId);
            if (validationError != null) {
                recordInvalidPurpose(policy, validationError, invalidPurposes, invalidRerankPurposes);
                continue;
            }

            LlmModelCatalog model = policy.getModel();
            LlmProvider provider = model.getProviderRef();
            String apiKey = cryptoService.decrypt(secretsByProviderId.get(provider.getId()));
            if (model.getUsageFamily() == UsageFamily.RERANK) {
                loadRerankConfig(policy, provider, apiKey, model, rerankConfigs, invalidRerankPurposes);
                continue;
            }
            LlmProperties.ProviderProperties providerConfig = buildProviderConfig(provider, policy, apiKey, model);

            recordRoute(policy, provider, model, providerConfig, llmRoutes, embeddingRoutes);
        }

        return new DatabaseRouteSnapshot(llmRoutes, embeddingRoutes, invalidPurposes, rerankConfigs, invalidRerankPurposes);
    }

    private boolean isDisabledRerankPolicy(LlmRoutingPolicy policy) {
        return isRerankPolicy(policy) && !Boolean.TRUE.equals(policy.getEnabled());
    }

    private boolean isRerankPolicy(LlmRoutingPolicy policy) {
        return policy.getModel() != null && policy.getModel().getUsageFamily() == UsageFamily.RERANK;
    }

    private void recordInvalidPurpose(
            LlmRoutingPolicy policy,
            String validationError,
            Map<String, String> invalidPurposes,
            Map<String, String> invalidRerankPurposes
    ) {
        if (isRerankPolicy(policy)) {
            invalidRerankPurposes.put(policy.getPurpose(), validationError);
            return;
        }
        invalidPurposes.put(policy.getPurpose(), validationError);
    }

    private void loadRerankConfig(
            LlmRoutingPolicy policy,
            LlmProvider provider,
            String apiKey,
            LlmModelCatalog model,
            Map<String, DatabaseRerankConfig> rerankConfigs,
            Map<String, String> invalidRerankPurposes
    ) {
        try {
            rerankConfigs.put(policy.getPurpose(), buildRerankConfig(provider, policy, apiKey, model));
        } catch (IllegalArgumentException exception) {
            invalidRerankPurposes.put(policy.getPurpose(), exception.getMessage());
        }
    }

    private void recordRoute(
            LlmRoutingPolicy policy,
            LlmProvider provider,
            LlmModelCatalog model,
            LlmProperties.ProviderProperties providerConfig,
            Map<String, LlmRoute> llmRoutes,
            Map<String, EmbeddingRoute> embeddingRoutes
    ) {
        if (model.getUsageFamily() == UsageFamily.EMBEDDING) {
            embeddingRoutes.put(policy.getPurpose(), new EmbeddingRoute(
                    provider.getProviderKey(),
                    policy.getPurpose(),
                    model.getModelCode(),
                    resolveInputType(model),
                    resolveEmbeddingDimension(model),
                    providerConfig
            ));
            return;
        }
        llmRoutes.put(policy.getPurpose(), new LlmRoute(
                provider.getProviderKey(),
                policy.getPurpose(),
                model.getModelCode(),
                providerConfig
        ));
    }

    private String validate(LlmRoutingPolicy policy, Map<Long, LlmProviderSecret> secretsByProviderId) {
        if (!Boolean.TRUE.equals(policy.getEnabled())) {
            return "LLM routing policy is disabled for purpose: " + policy.getPurpose();
        }
        if (policy.getModel() == null) {
            return "LLM routing model is missing for purpose: " + policy.getPurpose();
        }
        if (!Boolean.TRUE.equals(policy.getModel().getActive())) {
            return "LLM model is disabled for purpose: " + policy.getPurpose();
        }
        if (policy.getModel().getProviderRef() == null) {
            return "LLM provider is missing for purpose: " + policy.getPurpose();
        }
        if (!Boolean.TRUE.equals(policy.getModel().getProviderRef().getEnabled())) {
            return "LLM provider is disabled for purpose: " + policy.getPurpose();
        }
        LlmProviderSecret secret = secretsByProviderId.get(policy.getModel().getProviderRef().getId());
        if (secret == null) {
            return "LLM provider secret is missing for purpose: " + policy.getPurpose();
        }
        try {
            cryptoService.decrypt(secret);
            return null;
        } catch (IllegalArgumentException ex) {
            return "LLM provider secret is invalid for purpose: " + policy.getPurpose();
        }
    }

    private LlmProperties.ProviderProperties buildProviderConfig(
            LlmProvider provider,
            LlmRoutingPolicy policy,
            String apiKey,
            LlmModelCatalog model
    ) {
        LlmProperties.ProviderProperties providerProperties = new LlmProperties.ProviderProperties();
        providerProperties.setBaseUrl(provider.getBaseUrl());
        providerProperties.setApiKey(apiKey);
        providerProperties.setTimeoutMs(policy.getTimeoutMs() != null ? policy.getTimeoutMs() : defaultTimeout(provider));
        providerProperties.setMaxRetries(policy.getMaxRetries() != null ? policy.getMaxRetries() : defaultMaxRetries(provider));
        providerProperties.setRetryBackoffMs(500);

        LlmProperties.TemplateProperties templateProperties = new LlmProperties.TemplateProperties();
        templateProperties.setEnabled(provider.getTemplateRoot() != null && !provider.getTemplateRoot().isBlank());
        templateProperties.setRoot(provider.getTemplateRoot());
        providerProperties.setTemplate(templateProperties);

        if (model.getUsageFamily() == UsageFamily.EMBEDDING) {
            LlmProperties.EmbeddingProperties embeddingProperties = new LlmProperties.EmbeddingProperties();
            embeddingProperties.setDimension(resolveEmbeddingDimension(model));
            LlmProperties.ModelProperties models = new LlmProperties.ModelProperties();
            models.put(policy.getPurpose(), model.getModelCode());
            embeddingProperties.setModels(models);
            String inputType = resolveInputType(model);
            if (inputType != null) {
                embeddingProperties.getInputTypes().put(policy.getPurpose(), inputType);
            }
            providerProperties.setEmbedding(embeddingProperties);
        } else {
            LlmProperties.ChatProperties chatProperties = new LlmProperties.ChatProperties();
            LlmProperties.ModelProperties models = new LlmProperties.ModelProperties();
            models.put(policy.getPurpose(), model.getModelCode());
            chatProperties.setModels(models);
            providerProperties.setChat(chatProperties);
        }
        return providerProperties;
    }

    private DatabaseRerankConfig buildRerankConfig(
            LlmProvider provider,
            LlmRoutingPolicy policy,
            String apiKey,
            LlmModelCatalog model
    ) {
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Rerank provider base URL is missing for purpose: " + policy.getPurpose());
        }
        if (model.getModelCode() == null || model.getModelCode().isBlank()) {
            throw new IllegalArgumentException("Rerank model code is missing for purpose: " + policy.getPurpose());
        }

        JsonNode metadata = readRoutingMetadata(policy);
        int preRerankCandidateCap = readRequiredPositiveInt(metadata, "preRerankCandidateCap", policy.getPurpose());
        int stage1TopN = readRequiredPositiveInt(metadata, "stage1TopN", policy.getPurpose());
        double stage1RelevanceThreshold = readRequiredDouble(metadata, "stage1RelevanceThreshold", policy.getPurpose());
        boolean dualQueryEnabled = readRequiredBoolean(metadata, "dualQueryEnabled", policy.getPurpose());
        if (preRerankCandidateCap > MAX_PRE_RERANK_CANDIDATE_CAP) {
            throw new IllegalArgumentException(
                    "Rerank metadata preRerankCandidateCap must be <= "
                            + MAX_PRE_RERANK_CANDIDATE_CAP
                            + " for purpose: "
                            + policy.getPurpose()
            );
        }
        if (stage1TopN > preRerankCandidateCap) {
            throw new IllegalArgumentException("Rerank metadata stage1TopN must be <= preRerankCandidateCap for purpose: " + policy.getPurpose());
        }

        return new DatabaseRerankConfig(
                policy.getPurpose(),
                provider.getProviderKey(),
                provider.getBaseUrl(),
                apiKey,
                model.getModelCode(),
                policy.getTimeoutMs() != null ? policy.getTimeoutMs() : defaultTimeout(provider),
                preRerankCandidateCap,
                stage1TopN,
                stage1RelevanceThreshold,
                dualQueryEnabled
        );
    }

    private int defaultTimeout(LlmProvider provider) {
        return provider.getDefaultTimeoutMs() != null ? provider.getDefaultTimeoutMs() : 30_000;
    }

    private int defaultMaxRetries(LlmProvider provider) {
        return provider.getDefaultMaxRetries() != null ? provider.getDefaultMaxRetries() : 3;
    }

    private int resolveEmbeddingDimension(LlmModelCatalog model) {
        JsonNode metadata = readMetadata(model);
        return metadata.path("dimension").asInt(2048);
    }

    private String resolveInputType(LlmModelCatalog model) {
        JsonNode metadata = readMetadata(model);
        if (metadata.path("inputType").isMissingNode() || metadata.path("inputType").isNull()) {
            return null;
        }
        String value = metadata.path("inputType").asText();
        return value == null || value.isBlank() ? null : value;
    }

    private JsonNode readMetadata(LlmModelCatalog model) {
        return readJson(model.getMetadata());
    }

    private JsonNode readRoutingMetadata(LlmRoutingPolicy policy) {
        return readJson(policy.getMetadata());
    }

    private JsonNode readJson(String rawJson) {
        try {
            return rawJson == null || rawJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(rawJson);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private int readRequiredPositiveInt(JsonNode metadata, String fieldName, String purpose) {
        JsonNode field = metadata.get(fieldName);
        if (field == null || !field.isNumber() || field.asInt() <= 0) {
            throw new IllegalArgumentException("Rerank metadata " + fieldName + " is invalid for purpose: " + purpose);
        }
        return field.asInt();
    }

    private double readRequiredDouble(JsonNode metadata, String fieldName, String purpose) {
        JsonNode field = metadata.get(fieldName);
        if (field == null || !field.isNumber()) {
            throw new IllegalArgumentException("Rerank metadata " + fieldName + " is invalid for purpose: " + purpose);
        }
        return field.asDouble();
    }

    private boolean readRequiredBoolean(JsonNode metadata, String fieldName, String purpose) {
        JsonNode field = metadata.get(fieldName);
        if (field == null || !field.isBoolean()) {
            throw new IllegalArgumentException("Rerank metadata " + fieldName + " is invalid for purpose: " + purpose);
        }
        return field.asBoolean();
    }

    public record DatabaseRouteSnapshot(
            Map<String, LlmRoute> llmRoutes,
            Map<String, EmbeddingRoute> embeddingRoutes,
            Map<String, String> invalidPurposes,
            Map<String, DatabaseRerankConfig> rerankConfigs,
            Map<String, String> invalidRerankPurposes
    ) {
    }
}
