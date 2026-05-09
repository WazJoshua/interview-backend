package com.josh.interviewj.ragqa.model;

import java.util.List;

public record FusedRetrievalResult(
        List<FusedChunk> selectedChunks,
        int candidateCount,
        int deduplicatedCount,
        int originalHitCount,
        int rewriteHitCount,
        int overlapHitCount,
        int sparseCandidateCount,
        int sparseSelectedCount,
        int sparseOnlyRescueCount,
        int crossBranchMismatchCount
) {
    public FusedRetrievalResult {
        selectedChunks = List.copyOf(selectedChunks);
    }
}
