package com.josh.interviewj.knowledgebase.service;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates embeddings for document chunk indexing.
 */
@Service
@RequiredArgsConstructor
public class DocumentEmbeddingService {

    private static final String PURPOSE_DOCUMENT = "kb_document_embedding";

    private final AiOperationGateway aiOperationGateway;

    /**
     * Generates an embedding tailored for document content.
     *
     * @param content document chunk content
     * @return embedding vector
     */
    public float[] embedDocument(String content) {
        throw new IllegalStateException("Document embedding requires an explicit business operation context");
    }

    public EmbeddingResponse embedDocumentWithUsage(String content) {
        throw new IllegalStateException("Document embedding requires an explicit business operation context");
    }

    public EmbeddingExecutionResult embedDocumentWithUsage(
            BusinessOperationContext operationContext,
            String invocationId,
            String content
    ) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.LLM_001, "Embedding input is required");
        }
        AiInvocationContext invocationContext = new AiInvocationContext(
                invocationId,
                PURPOSE_DOCUMENT,
                UsageFamily.EMBEDDING,
                "KB_INGESTION_CREDITS",
                false,
                Map.of()
        );
        AiInvocationResult result = aiOperationGateway.executeInvocation(
                operationContext,
                invocationContext,
                AiInvocationInput.embedding(content)
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
