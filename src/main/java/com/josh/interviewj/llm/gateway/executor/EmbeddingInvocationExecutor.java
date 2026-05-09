package com.josh.interviewj.llm.gateway.executor;

import com.josh.interviewj.llm.EmbeddingService;
import com.josh.interviewj.llm.core.EmbeddingRequest;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationKind;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmbeddingInvocationExecutor implements AiCapabilityExecutor {

    private final EmbeddingService embeddingService;

    @Override
    public AiInvocationKind supportsKind() {
        return AiInvocationKind.EMBEDDING;
    }

    @Override
    public AiInvocationResult execute(AiInvocationContext invocationContext, AiInvocationInput input) {
        EmbeddingResponse response = embeddingService.generate(new EmbeddingRequest(
                invocationContext.purpose(),
                input.embeddingInput()
        ));
        return AiInvocationResult.fromEmbedding(response);
    }
}
