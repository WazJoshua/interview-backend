package com.josh.interviewj.llm.gateway.executor;

import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationKind;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.ragqa.model.RerankResponse;
import com.josh.interviewj.ragqa.service.RerankClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RerankInvocationExecutor implements AiCapabilityExecutor {

    private final RerankClient rerankClient;

    @Override
    public AiInvocationKind supportsKind() {
        return AiInvocationKind.RERANK;
    }

    @Override
    public AiInvocationResult execute(AiInvocationContext invocationContext, AiInvocationInput input) {
        RerankResponse response = rerankClient.rerank(
                invocationContext.purpose(),
                input.rerankQuery(),
                input.rerankDocuments()
        );
        return AiInvocationResult.fromRerank(rerankClient.providerKey(invocationContext.purpose()), response);
    }
}
