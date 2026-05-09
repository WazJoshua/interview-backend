package com.josh.interviewj.knowledgebase.preprocessing.sparse;

import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record ExtractedEntity(
        String canonicalToken,
        List<String> normalizedVariants,
        ExtractedEntityCategory category,
        String redactionReason
) {

    public ExtractedEntity {
        canonicalToken = canonicalToken == null ? "" : canonicalToken;
        normalizedVariants = normalizedVariants == null ? List.of() : List.copyOf(normalizedVariants);
    }
}
