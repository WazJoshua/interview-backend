package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.llm.routing.EmbeddingRoute;
import com.josh.interviewj.llm.routing.EmbeddingRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves the effective document-embedding configuration from runtime LLM routing.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseEmbeddingConfigService {

    private static final String DOCUMENT_EMBEDDING_PURPOSE = "kb_document_embedding";

    private final EmbeddingRouter embeddingRouter;

    public KnowledgeBaseEmbeddingConfig getCurrentDocumentEmbedding() {
        EmbeddingRoute route = embeddingRouter.resolve(DOCUMENT_EMBEDDING_PURPOSE);
        return new KnowledgeBaseEmbeddingConfig(route.model(), route.dimension());
    }

    public record KnowledgeBaseEmbeddingConfig(
            String model,
            Integer dimension
    ) {
    }
}
