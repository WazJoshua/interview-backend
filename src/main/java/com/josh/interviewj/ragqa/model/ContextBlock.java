package com.josh.interviewj.ragqa.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ContextBlock(
        Long documentId,
        UUID documentExternalId,
        String documentName,
        List<String> sectionPath,
        List<Integer> seedChunkIndexes,
        List<Integer> includedChunkIndexes,
        String mergedText,
        int estimatedTokens,
        double stage1BestScore,
        Map<String, Object> metadataSummary,
        Set<RetrievalProvenance> provenances,
        AssemblyStrategy assemblyStrategy
) {
    public ContextBlock {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        seedChunkIndexes = seedChunkIndexes == null ? List.of() : List.copyOf(seedChunkIndexes);
        includedChunkIndexes = includedChunkIndexes == null ? List.of() : List.copyOf(includedChunkIndexes);
        metadataSummary = metadataSummary == null ? Map.of() : Map.copyOf(metadataSummary);
        provenances = provenances == null ? Set.of() : Set.copyOf(provenances);
    }

    public enum AssemblyStrategy {
        SECTION_PRIORITY,
        ADJACENCY_FALLBACK,
        SINGLE_CHUNK
    }
}
