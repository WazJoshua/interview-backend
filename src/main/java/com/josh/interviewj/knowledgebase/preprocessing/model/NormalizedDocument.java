package com.josh.interviewj.knowledgebase.preprocessing.model;

import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewProjection;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder(toBuilder = true)
public record NormalizedDocument(
        DocumentSourceType sourceType,
        String fileName,
        String title,
        Map<String, Object> metadata,
        List<NormalizedBlock> blocks,
        List<DocumentWarning> warnings,
        DocumentQualitySummary qualitySummary,
        ReviewProjection reviewProjection
) {

    public NormalizedDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        qualitySummary = qualitySummary == null ? DocumentQualitySummary.empty() : qualitySummary;
        reviewProjection = reviewProjection == null ? ReviewProjection.builder().build() : reviewProjection;
    }
}
