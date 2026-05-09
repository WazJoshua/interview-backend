package com.josh.interviewj.usage.service;

import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.ragqa.model.RerankResponse;
import com.josh.interviewj.usage.model.LlmModelCatalog;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.repository.LlmModelCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UsageContextFactory {

    private final LlmModelCatalogRepository llmModelCatalogRepository;

    public UsageOperationContext successFromLlm(
            String purpose,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            LlmResponse response
    ) {
        return fromLlm(purpose, resourceType, resourceExternalId, operationId, userId, UsageBusinessOutcome.SUCCESS, null, response);
    }

    public UsageOperationContext failureFromLlm(
            String purpose,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            UsageBusinessOutcome outcome,
            String failureReason,
            LlmResponse response
    ) {
        return fromLlm(purpose, resourceType, resourceExternalId, operationId, userId, outcome, failureReason, response);
    }

    public UsageOperationContext successFromEmbedding(
            String purpose,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            EmbeddingResponse response
    ) {
        return fromEmbedding(
                purpose,
                resourceType,
                resourceExternalId,
                operationId,
                userId,
                UsageBusinessOutcome.SUCCESS,
                null,
                response
        );
    }

    public UsageOperationContext failureFromEmbedding(
            String purpose,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            UsageBusinessOutcome outcome,
            String failureReason,
            EmbeddingResponse response
    ) {
        return fromEmbedding(purpose, resourceType, resourceExternalId, operationId, userId, outcome, failureReason, response);
    }

    public UsageOperationContext successFromRerank(
            String purpose,
            String provider,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            RerankResponse response
    ) {
        return fromRerank(
                purpose,
                provider,
                resourceType,
                resourceExternalId,
                operationId,
                userId,
                UsageBusinessOutcome.SUCCESS,
                null,
                response
        );
    }

    public UsageOperationContext failureFromRerank(
            String purpose,
            String provider,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            UsageBusinessOutcome outcome,
            String failureReason,
            RerankResponse response
    ) {
        return fromRerank(purpose, provider, resourceType, resourceExternalId, operationId, userId, outcome, failureReason, response);
    }

    private UsageOperationContext fromLlm(
            String purpose,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            UsageBusinessOutcome outcome,
            String failureReason,
            LlmResponse response
    ) {
        if (response == null || response.usage() == null) {
            return null;
        }
        LlmIdentity identity = resolveIdentity(response.provider(), response.model(), response.usage().usageFamily());
        return new UsageOperationContext(
                purpose,
                response.provider(),
                response.model(),
                response.usage(),
                resourceType,
                resourceExternalId,
                operationId,
                userId,
                outcome,
                failureReason,
                identity.providerId(),
                identity.modelId()
        );
    }

    private UsageOperationContext fromEmbedding(
            String purpose,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            UsageBusinessOutcome outcome,
            String failureReason,
            EmbeddingResponse response
    ) {
        if (response == null || response.usage() == null) {
            return null;
        }
        LlmIdentity identity = resolveIdentity(response.provider(), response.model(), response.usage().usageFamily());
        return new UsageOperationContext(
                purpose,
                response.provider(),
                response.model(),
                response.usage(),
                resourceType,
                resourceExternalId,
                operationId,
                userId,
                outcome,
                failureReason,
                identity.providerId(),
                identity.modelId()
        );
    }

    private UsageOperationContext fromRerank(
            String purpose,
            String provider,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            UsageBusinessOutcome outcome,
            String failureReason,
            RerankResponse response
    ) {
        if (response == null || response.usage() == null) {
            return null;
        }
        LlmIdentity identity = resolveIdentity(provider, response.model(), response.usage().usageFamily());
        return new UsageOperationContext(
                purpose,
                provider,
                response.model(),
                response.usage(),
                resourceType,
                resourceExternalId,
                operationId,
                userId,
                outcome,
                failureReason,
                identity.providerId(),
                identity.modelId()
        );
    }

    private LlmIdentity resolveIdentity(String provider, String modelCode, UsageFamily usageFamily) {
        return llmModelCatalogRepository.findByProviderAndModelCodeAndUsageFamilyWithProvider(provider, modelCode, usageFamily)
                .map(model -> new LlmIdentity(resolveProviderId(model), model.getId()))
                .orElseGet(() -> new LlmIdentity(null, null));
    }

    private Long resolveProviderId(LlmModelCatalog model) {
        return model.getProviderRef() == null ? null : model.getProviderRef().getId();
    }

    private record LlmIdentity(Long providerId, Long modelId) {
    }
}
