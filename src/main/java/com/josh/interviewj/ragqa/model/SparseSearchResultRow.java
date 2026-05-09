package com.josh.interviewj.ragqa.model;

import java.util.UUID;

public record SparseSearchResultRow(
        UUID documentExternalId,
        Long documentId,
        String documentName,
        Integer chunkIndex,
        String content,
        double contentRank,
        double entityRank,
        double exactBoost,
        double finalSparseScore
) {
}
