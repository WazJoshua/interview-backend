package com.josh.interviewj.knowledgebase.preprocessing.model;

import lombok.Builder;

import java.util.Map;

@Builder
public record DocumentWarning(
        String code,
        DocumentWarningCategory category,
        String message,
        Map<String, Object> metadata
) {

    public DocumentWarning {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
