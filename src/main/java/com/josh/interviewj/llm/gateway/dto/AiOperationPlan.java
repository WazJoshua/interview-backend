package com.josh.interviewj.llm.gateway.dto;

import java.util.List;

public record AiOperationPlan(List<Step> steps) {

    public AiOperationPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public record Step(
            AiInvocationContext invocationContext,
            AiInvocationInput input,
            ExecutionDisposition executionDisposition,
            InvocationUsageOutcome usageOutcome,
            String failureReason
    ) {
    }
}
