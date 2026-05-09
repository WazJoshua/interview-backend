package com.josh.interviewj.llm.gateway;

import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.AiOperationPlan;
import com.josh.interviewj.llm.gateway.dto.AiOperationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.llm.gateway.executor.AiCapabilityExecutorRegistry;
import com.josh.interviewj.llm.gateway.support.AiGatewayUsageBridge;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.service.CreditsGuardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DefaultAiOperationGateway implements AiOperationGateway {

    private final CreditsGuardService creditsGuardService;
    private final AiCapabilityExecutorRegistry executorRegistry;
    private final AiGatewayUsageBridge usageBridge;

    @Override
    public BusinessOperationContext prepareOperation(BusinessOperationContext operationContext) {
        for (String chargeBucket : operationContext.preflightChargeBuckets().stream().distinct().toList()) {
            creditsGuardService.requirePositiveSpendableCredits(
                    operationContext.userId(),
                    chargeBucket,
                    resolvePreflightUsageFamily(operationContext),
                    operationContext.resourceType(),
                    operationContext.resourceExternalId(),
                    preflightOperationId(operationContext),
                    operationContext.businessOperationId()
            );
        }
        return operationContext;
    }

    @Override
    public AiInvocationResult executeInvocation(
            BusinessOperationContext operationContext,
            AiInvocationContext invocationContext,
            AiInvocationInput input
    ) {
        validateBucket(operationContext, invocationContext);
        return executorRegistry.get(input.kind()).execute(invocationContext, input);
    }

    @Override
    public void submitInvocationOutcome(
            BusinessOperationContext operationContext,
            AiInvocationContext invocationContext,
            AiInvocationResult result,
            ExecutionDisposition executionDisposition,
            InvocationUsageOutcome usageOutcome,
            String failureReason
    ) {
        usageBridge.recordInvocationOutcome(
                operationContext,
                invocationContext,
                result,
                executionDisposition,
                usageOutcome,
                failureReason
        );
    }

    @Override
    public AiOperationResult runOperation(BusinessOperationContext operationContext, AiOperationPlan plan) {
        prepareOperation(operationContext);

        List<AiInvocationResult> invocationResults = new ArrayList<>();
        InvocationUsageOutcome aggregateOutcome = InvocationUsageOutcome.SUCCESS;
        for (AiOperationPlan.Step step : plan.steps()) {
            AiInvocationResult result = executeInvocation(operationContext, step.invocationContext(), step.input());
            invocationResults.add(result);
            submitInvocationOutcome(
                    operationContext,
                    step.invocationContext(),
                    result,
                    step.executionDisposition(),
                    step.usageOutcome(),
                    step.failureReason()
            );
            aggregateOutcome = aggregate(aggregateOutcome, step.usageOutcome());
        }
        return new AiOperationResult(operationContext.businessOperationId(), invocationResults, aggregateOutcome);
    }

    private void validateBucket(BusinessOperationContext operationContext, AiInvocationContext invocationContext) {
        String expectedChargeBucket = invocationContext.expectedChargeBucket();
        if (expectedChargeBucket == null || expectedChargeBucket.isBlank()) {
            return;
        }
        if (!operationContext.preflightChargeBuckets().contains(expectedChargeBucket)) {
            throw new IllegalArgumentException("Invocation charge bucket is not declared in operation preflight: " + expectedChargeBucket);
        }
    }

    private InvocationUsageOutcome aggregate(
            InvocationUsageOutcome current,
            InvocationUsageOutcome next
    ) {
        if (current == InvocationUsageOutcome.FAILED_NON_CHARGEABLE
                || next == InvocationUsageOutcome.FAILED_NON_CHARGEABLE) {
            return InvocationUsageOutcome.FAILED_NON_CHARGEABLE;
        }
        if (current == InvocationUsageOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE
                || next == InvocationUsageOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE) {
            return InvocationUsageOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE;
        }
        return InvocationUsageOutcome.SUCCESS;
    }

    private String resolvePreflightUsageFamily(BusinessOperationContext operationContext) {
        Object configuredUsageFamily = operationContext.auditMetadata().get("preflightUsageFamily");
        if (configuredUsageFamily instanceof String usageFamilyText) {
            String normalized = usageFamilyText.trim().toUpperCase(Locale.ROOT);
            for (UsageFamily usageFamily : UsageFamily.values()) {
                if (usageFamily.name().equals(normalized)) {
                    return usageFamily.name();
                }
            }
        }

        String scenario = operationContext.scenario();
        if (scenario == null || scenario.isBlank()) {
            return UsageFamily.CHAT.name();
        }

        String normalizedScenario = scenario.toLowerCase(Locale.ROOT);
        if (normalizedScenario.contains("rerank")) {
            return UsageFamily.RERANK.name();
        }
        if (normalizedScenario.contains("embedding")) {
            return UsageFamily.EMBEDDING.name();
        }
        return UsageFamily.CHAT.name();
    }

    private String preflightOperationId(BusinessOperationContext operationContext) {
        return "preflight:" + operationContext.businessOperationId();
    }
}
