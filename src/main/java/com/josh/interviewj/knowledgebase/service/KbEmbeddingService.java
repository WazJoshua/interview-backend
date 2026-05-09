package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.llm.core.EmbeddingClient;
import com.josh.interviewj.llm.core.EmbeddingRequest;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Calls the external embedding provider and normalizes the returned vectors.
 */
@Service
@RequiredArgsConstructor
public class KbEmbeddingService {

    private static final String PURPOSE_QUERY = "kb_query_embedding";
    private static final String PURPOSE_DOCUMENT = "kb_document_embedding";

    private final EmbeddingClient embeddingClient;

    /**
     * Generates an embedding tailored for query-time retrieval.
     *
     * @param question user question
     * @return embedding vector
     */
    public float[] embedQuery(String question) {
        return embedQueryWithUsage(question).vector();
    }

    public EmbeddingResponse embedQueryWithUsage(String question) {
        return embed(PURPOSE_QUERY, question);
    }

    /**
     * Generates an embedding tailored for document content.
     *
     * @param content document chunk content
     * @return embedding vector
     */
    public float[] embedDocument(String content) {
        return embedDocumentWithUsage(content).vector();
    }

    public EmbeddingResponse embedDocumentWithUsage(String content) {
        return embed(PURPOSE_DOCUMENT, content);
    }

    /**
     * Delegates to the provider-neutral embedding client.
     *
     * @param purpose embedding routing purpose
     * @param input source text
     * @return embedding vector
     */
    private EmbeddingResponse embed(String purpose, String input) {
        if (input == null || input.isBlank()) {
            throw new BusinessException(ErrorCode.LLM_001, "Embedding input is required");
        }
        return embeddingClient.generate(new EmbeddingRequest(purpose, input));
    }
}
