package com.josh.interviewj.ragqa.model;

import java.util.Set;
import java.util.UUID;

public record RankedChunkCandidate(
        Long documentId,
        UUID documentExternalId,
        String documentName,
        Integer chunkIndex,
        String content,
        String metadata,
        Set<RetrievalProvenance> provenances,
        double stage1RelevanceScore,
        double denseSimilarity,
        double rrfScore
) {
    public RankedChunkCandidate {
        provenances = Set.copyOf(provenances);
    }
}
