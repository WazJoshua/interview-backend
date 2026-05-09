package com.josh.interviewj.llm.gateway.support;

import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.usage.service.UsageBusinessOutcome;
import com.josh.interviewj.usage.service.UsageContextFactory;
import com.josh.interviewj.usage.service.UsageOperationContext;
import com.josh.interviewj.usage.service.TransactionalUsageSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AiGatewayUsageBridge {

    private final UsageContextFactory usageContextFactory;
    private final TransactionalUsageSettlementService transactionalUsageSettlementService;

    public void recordInvocationOutcome(
            BusinessOperationContext operationContext,
            AiInvocationContext invocationContext,
            AiInvocationResult result,
            ExecutionDisposition executionDisposition,
            InvocationUsageOutcome usageOutcome,
            String failureReason
    ) {
        if (executionDisposition != ExecutionDisposition.EXECUTED || result == null || result.usage() == null) {
            return;
        }

        UsageBusinessOutcome mappedOutcome = mapOutcome(usageOutcome);
        UsageOperationContext usageContext = switch (result.kind()) {
            case CHAT -> usageContextFactory.failureFromLlm(
                    invocationContext.purpose(),
                    operationContext.resourceType(),
                    operationContext.resourceExternalId(),
                    invocationContext.invocationId(),
                    operationContext.userId(),
                    mappedOutcome,
                    failureReason,
                    result.llmResponse()
            );
            case EMBEDDING -> usageContextFactory.failureFromEmbedding(
                    invocationContext.purpose(),
                    operationContext.resourceType(),
                    operationContext.resourceExternalId(),
                    invocationContext.invocationId(),
                    operationContext.userId(),
                    mappedOutcome,
                    failureReason,
                    result.embeddingResponse()
            );
            case RERANK -> usageContextFactory.failureFromRerank(
                    invocationContext.purpose(),
                    result.provider(),
                    operationContext.resourceType(),
                    operationContext.resourceExternalId(),
                    invocationContext.invocationId(),
                    operationContext.userId(),
                    mappedOutcome,
                    failureReason,
                    result.rerankResponse()
            );
        };

        if (usageContext == null) {
            return;
        }

        // Merge metadata: result.metadata() overrides invocationContext.auditMetadata() for same keys
        Map<String, Object> mergedMetadata = mergeMetadata(
                invocationContext.auditMetadata(),
                result.metadata()
        );

        UsageOperationContext settlementContext = new UsageOperationContext(
                usageContext.purpose(),
                usageContext.provider(),
                usageContext.modelCode(),
                usageContext.providerUsage(),
                usageContext.resourceType(),
                usageContext.resourceExternalId(),
                usageContext.operationId(),
                operationContext.businessOperationId(),
                usageContext.userId(),
                usageContext.businessOutcome(),
                usageContext.failureReason(),
                executionDisposition.name(),
                usageContext.providerId(),
                usageContext.modelId(),
                mergedMetadata
        );

        transactionalUsageSettlementService.recordInNewTransaction(settlementContext);
    }

    /**
     * Merge audit metadata from invocation context with result metadata.
     * Result metadata takes precedence for overlapping keys.
     */
    private Map<String, Object> mergeMetadata(Map<String, Object> auditMetadata, Map<String, Object> resultMetadata) {
        if (auditMetadata == null && resultMetadata == null) {
            return Map.of();
        }
        if (auditMetadata == null) {
            return resultMetadata == null ? Map.of() : Map.copyOf(resultMetadata);
        }
        if (resultMetadata == null) {
            return Map.copyOf(auditMetadata);
        }

        Map<String, Object> merged = new HashMap<>(auditMetadata);
        merged.putAll(resultMetadata);
        return Map.copyOf(merged);
    }

    private UsageBusinessOutcome mapOutcome(InvocationUsageOutcome usageOutcome) {
        return switch (usageOutcome) {
            case SUCCESS -> UsageBusinessOutcome.SUCCESS;
            case FAILED_NON_CHARGEABLE -> UsageBusinessOutcome.FAILED_NON_CHARGEABLE;
            case FALLBACK_RECOVERED_NON_CHARGEABLE -> UsageBusinessOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE;
        };
    }
}