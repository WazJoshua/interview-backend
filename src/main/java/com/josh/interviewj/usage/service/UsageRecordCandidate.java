package com.josh.interviewj.usage.service;

import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.ragqa.model.RerankResponse;

/**
 * @deprecated Temporary compatibility type for historical usage recording flows.
 * New business code must use AiOperationGateway instead.
 */
@Deprecated(forRemoval = true)
public record UsageRecordCandidate(
        String purpose,
        String resourceType,
        String resourceExternalId,
        String operationId,
        Long userId,
        String rerankProvider,
        LlmResponse llmResponse,
        EmbeddingResponse embeddingResponse,
        RerankResponse rerankResponse
) {

    public static UsageRecordCandidate ofLlm(
            String purpose,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            LlmResponse response
    ) {
        return new UsageRecordCandidate(
                purpose,
                resourceType,
                resourceExternalId,
                operationId,
                userId,
                null,
                response,
                null,
                null
        );
    }

    public static UsageRecordCandidate ofEmbedding(
            String purpose,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            EmbeddingResponse response
    ) {
        return new UsageRecordCandidate(
                purpose,
                resourceType,
                resourceExternalId,
                operationId,
                userId,
                null,
                null,
                response,
                null
        );
    }

    public static UsageRecordCandidate ofRerank(
            String purpose,
            String provider,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            RerankResponse response
    ) {
        return new UsageRecordCandidate(
                purpose,
                resourceType,
                resourceExternalId,
                operationId,
                userId,
                provider,
                null,
                null,
                response
        );
    }

    public UsageOperationContext toSuccess(UsageContextFactory usageContextFactory) {
        if (llmResponse != null) {
            return usageContextFactory.successFromLlm(
                    purpose,
                    resourceType,
                    resourceExternalId,
                    operationId,
                    userId,
                    llmResponse
            );
        }
        if (embeddingResponse != null) {
            return usageContextFactory.successFromEmbedding(
                    purpose,
                    resourceType,
                    resourceExternalId,
                    operationId,
                    userId,
                    embeddingResponse
            );
        }
        if (rerankResponse != null) {
            return usageContextFactory.successFromRerank(
                    purpose,
                    rerankProvider,
                    resourceType,
                    resourceExternalId,
                    operationId,
                    userId,
                    rerankResponse
            );
        }
        return null;
    }

    public UsageOperationContext toFailure(
            UsageContextFactory usageContextFactory,
            UsageBusinessOutcome outcome,
            String failureReason
    ) {
        if (llmResponse != null) {
            return usageContextFactory.failureFromLlm(
                    purpose,
                    resourceType,
                    resourceExternalId,
                    operationId,
                    userId,
                    outcome,
                    failureReason,
                    llmResponse
            );
        }
        if (embeddingResponse != null) {
            return usageContextFactory.failureFromEmbedding(
                    purpose,
                    resourceType,
                    resourceExternalId,
                    operationId,
                    userId,
                    outcome,
                    failureReason,
                    embeddingResponse
            );
        }
        if (rerankResponse != null) {
            return usageContextFactory.failureFromRerank(
                    purpose,
                    rerankProvider,
                    resourceType,
                    resourceExternalId,
                    operationId,
                    userId,
                    outcome,
                    failureReason,
                    rerankResponse
            );
        }
        return null;
    }
}
