package com.josh.interviewj.ragqa.model;

import java.util.Set;
import java.util.UUID;

public record PreRerankCandidate(
        Long documentId,
        UUID documentExternalId,
        String documentName,
        Integer chunkIndex,
        String content,
        double rrfScore,
        double bestDenseSimilarity,
        int bestDenseRank,
        boolean sparseOnlyRescue,
        boolean hasDenseProvenance,
        boolean hasSparseProvenance,
        Set<RetrievalProvenance> provenances,
        String metadata
) {
    public PreRerankCandidate {
        provenances = Set.copyOf(provenances);
    }
}
