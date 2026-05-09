package com.josh.interviewj.knowledgebase.preprocessing.model;

import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder(toBuilder = true)
public record ParsedDocument(
        DocumentSourceType sourceType,
        String fileName,
        String title,
        Map<String, Object> rawMetadata,
        List<ParsedBlock> blocks,
        List<DocumentWarning> warnings
) {

    public ParsedDocument {
        rawMetadata = rawMetadata == null ? Map.of() : Map.copyOf(rawMetadata);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
