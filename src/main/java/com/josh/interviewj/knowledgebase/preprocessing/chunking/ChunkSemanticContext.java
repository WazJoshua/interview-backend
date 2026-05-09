package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import lombok.Builder;

import java.util.List;
import java.util.Objects;

/**
 * Semantic context used by parent-context injection and persistence metadata.
 */
@Builder(toBuilder = true)
public record ChunkSemanticContext(
        List<String> sectionPath,
        List<String> blockTypes,
        List<Integer> pageNumbers
) {

    public ChunkSemanticContext {
        sectionPath = sanitizeStrings(sectionPath);
        blockTypes = sanitizeStrings(blockTypes);
        pageNumbers = pageNumbers == null
                ? List.of()
                : pageNumbers.stream().filter(Objects::nonNull).toList();
    }

    private static List<String> sanitizeStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
