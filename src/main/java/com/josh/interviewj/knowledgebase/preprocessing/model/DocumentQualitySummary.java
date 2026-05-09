package com.josh.interviewj.knowledgebase.preprocessing.model;

import lombok.Builder;

import java.util.Map;

@Builder(toBuilder = true)
public record DocumentQualitySummary(
        int originalBlockCount,
        int normalizedBlockCount,
        int removedEmptyBlockCount,
        int removedHeaderFooterCount,
        int removedPageNumberCount,
        int removedTocFragmentCount,
        int droppedLowSignalBlockCount,
        int softDeindexedLowSignalBlockCount,
        int protectedLowSignalBlockCount,
        int legacyWarnedLowSignalBlockCount,
        int warnedLowSignalBlockCount,
        int deduplicatedBlockCount,
        int warningCount,
        boolean hasStructuralWarnings,
        boolean hasReadabilityWarnings,
        boolean fitForMainIndex,
        Map<String, Object> metrics
) {

    public DocumentQualitySummary {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public static DocumentQualitySummary empty() {
        return DocumentQualitySummary.builder()
                .fitForMainIndex(true)
                .build();
    }
}
