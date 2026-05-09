package com.josh.interviewj.knowledgebase.preprocessing.sparse;

import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record ChunkSparseMaterialization(
        String sparseContentText,
        String sparseEntitiesText,
        List<String> sparseExactTerms,
        String metadataJson
) {

    public ChunkSparseMaterialization {
        sparseContentText = sparseContentText == null ? "" : sparseContentText;
        sparseEntitiesText = sparseEntitiesText == null ? "" : sparseEntitiesText;
        sparseExactTerms = sparseExactTerms == null ? List.of() : List.copyOf(sparseExactTerms);
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
    }
}
