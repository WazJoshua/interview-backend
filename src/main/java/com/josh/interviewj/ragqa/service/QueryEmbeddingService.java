package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.usage.model.UsageFamily;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Generates embeddings for query-time retrieval.
 */
@Service
@RequiredArgsConstructor
public class QueryEmbeddingService {

    private static final String PURPOSE_QUERY = "kb_query_embedding";

    private final AiOperationGateway aiOperationGateway;

    /**
     * Generates an embedding tailored for query-time retrieval.
     *
     * @param question user question
     * @return embedding vector
     */
    public float[] embedQuery(String question) {
        throw new IllegalStateException("Query embedding requires an explicit business operation context");
    }

    public EmbeddingResponse embedQueryWithUsage(String question) {
        throw new IllegalStateException("Query embedding requires an explicit business operation context");
    }

    public EmbeddingExecutionResult embedQueryWithUsage(
            BusinessOperationContext operationContext,
            String invocationId,
            String question
    ) {
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.LLM_001, "Embedding input is required");
        }
        AiInvocationContext invocationContext = new AiInvocationContext(
                invocationId,
                PURPOSE_QUERY,
                UsageFamily.EMBEDDING,
                "KB_QUERY_CREDITS",
                false,
                Map.of()
        );
        AiInvocationResult result = aiOperationGateway.executeInvocation(
                operationContext,
                invocationContext,
                AiInvocationInput.embedding(question)
        );
        return new EmbeddingExecutionResult(result.embeddingResponse(), invocationContext, result);
    }

    public record EmbeddingExecutionResult(
            EmbeddingResponse response,
            AiInvocationContext invocationContext,
            AiInvocationResult invocationResult
    ) {
    }
}
