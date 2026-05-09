package com.josh.interviewj.ragqa.model;

import java.util.UUID;

public record RetrievedChunk(
        QueryVariant queryVariant,
        RetrievalMode retrievalMode,
        UUID documentExternalId,
        Long documentId,
        String documentName,
        Integer chunkIndex,
        String content,
        double similarity,
        int branchRank,
        String metadata
) {
    public RetrievedChunk(
            QueryVariant queryVariant,
            RetrievalMode retrievalMode,
            UUID documentExternalId,
            Long documentId,
            String documentName,
            Integer chunkIndex,
            String content,
            double similarity,
            int branchRank
    ) {
        this(queryVariant, retrievalMode, documentExternalId, documentId,
                documentName, chunkIndex, content, similarity, branchRank, null);
    }
}
