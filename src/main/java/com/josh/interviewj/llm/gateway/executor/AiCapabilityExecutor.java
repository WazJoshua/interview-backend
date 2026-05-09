package com.josh.interviewj.llm.gateway.executor;

import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationKind;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;

public interface AiCapabilityExecutor {

    AiInvocationKind supportsKind();

    AiInvocationResult execute(AiInvocationContext invocationContext, AiInvocationInput input);
}
