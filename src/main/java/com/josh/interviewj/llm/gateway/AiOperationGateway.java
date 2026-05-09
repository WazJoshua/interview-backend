package com.josh.interviewj.llm.gateway;

import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.AiOperationPlan;
import com.josh.interviewj.llm.gateway.dto.AiOperationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;

public interface AiOperationGateway {

    BusinessOperationContext prepareOperation(BusinessOperationContext operationContext);

    AiInvocationResult executeInvocation(
            BusinessOperationContext operationContext,
            AiInvocationContext invocationContext,
            AiInvocationInput input
    );

    void submitInvocationOutcome(
            BusinessOperationContext operationContext,
            AiInvocationContext invocationContext,
            AiInvocationResult result,
            ExecutionDisposition executionDisposition,
            InvocationUsageOutcome usageOutcome,
            String failureReason
    );

    AiOperationResult runOperation(BusinessOperationContext operationContext, AiOperationPlan plan);
}
