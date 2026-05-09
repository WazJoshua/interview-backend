package com.josh.interviewj.llm.gateway.dto;

import java.util.List;

public record AiOperationResult(
        String businessOperationId,
        List<AiInvocationResult> invocationResults,
        InvocationUsageOutcome aggregateOutcome
) {

    public AiOperationResult {
        invocationResults = invocationResults == null ? List.of() : List.copyOf(invocationResults);
    }
}
