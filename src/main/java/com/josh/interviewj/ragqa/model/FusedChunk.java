package com.josh.interviewj.ragqa.model;

import java.util.Set;
import java.util.UUID;

public record FusedChunk(
        UUID documentExternalId,
        Long documentId,
        String documentName,
        Integer chunkIndex,
        String content,
        double similarity,
        double rrfScore,
        Set<RetrievalProvenance> provenances
) {
    public FusedChunk {
        provenances = Set.copyOf(provenances);
    }
}
