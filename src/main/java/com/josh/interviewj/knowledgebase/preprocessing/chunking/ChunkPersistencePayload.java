package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import lombok.Builder;

import java.util.List;

/**
 * Final persistence payload for one chunk.
 */
@Builder(toBuilder = true)
public record ChunkPersistencePayload(
        String metadataJson,
        String sparseContentText,
        String sparseEntitiesText,
        List<String> sparseExactTerms
) {

    public ChunkPersistencePayload {
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
        sparseContentText = sparseContentText == null ? "" : sparseContentText;
        sparseEntitiesText = sparseEntitiesText == null ? "" : sparseEntitiesText;
        sparseExactTerms = sparseExactTerms == null ? List.of() : List.copyOf(sparseExactTerms);
    }
}
