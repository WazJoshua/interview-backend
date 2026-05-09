package com.josh.interviewj.usage.service;

import com.josh.interviewj.llm.core.ProviderUsage;

import java.util.Map;

public record UsageOperationContext(
        String purpose,
        String provider,
        String modelCode,
        ProviderUsage providerUsage,
        String resourceType,
        String resourceExternalId,
        String operationId,
        String businessOperationId,
        Long userId,
        UsageBusinessOutcome businessOutcome,
        String failureReason,
        String executionDisposition,
        Long providerId,
        Long modelId,
        Map<String, Object> metadata
) {

    public UsageOperationContext(
            String purpose,
            String provider,
            String modelCode,
            ProviderUsage providerUsage,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            UsageBusinessOutcome businessOutcome,
            String failureReason,
            Long providerId,
            Long modelId
    ) {
        this(
                purpose,
                provider,
                modelCode,
                providerUsage,
                resourceType,
                resourceExternalId,
                operationId,
                null,
                userId,
                businessOutcome,
                failureReason,
                "EXECUTED",
                providerId,
                modelId,
                Map.of()
        );
    }

    public UsageOperationContext(
            String purpose,
            String provider,
            String modelCode,
            ProviderUsage providerUsage,
            String resourceType,
            String resourceExternalId,
            String operationId,
            Long userId,
            UsageBusinessOutcome businessOutcome,
            String failureReason
    ) {
        this(
                purpose,
                provider,
                modelCode,
                providerUsage,
                resourceType,
                resourceExternalId,
                operationId,
                null,
                userId,
                businessOutcome,
                failureReason,
                "EXECUTED",
                null,
                null,
                Map.of()
        );
    }

    public UsageOperationContext(
            String purpose,
            String provider,
            String modelCode,
            ProviderUsage providerUsage,
            String resourceType,
            String resourceExternalId,
            String operationId,
            String businessOperationId,
            Long userId,
            UsageBusinessOutcome businessOutcome,
            String failureReason
    ) {
        this(
                purpose,
                provider,
                modelCode,
                providerUsage,
                resourceType,
                resourceExternalId,
                operationId,
                businessOperationId,
                userId,
                businessOutcome,
                failureReason,
                "EXECUTED",
                null,
                null,
                Map.of()
        );
    }

    public UsageOperationContext {
        if (executionDisposition == null || executionDisposition.isBlank()) {
            executionDisposition = "EXECUTED";
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }
}