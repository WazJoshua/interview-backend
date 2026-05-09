package com.josh.interviewj.knowledgebase.preprocessing.model;

import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder(toBuilder = true)
public record ParsedBlock(
        ParsedBlockType type,
        String text,
        int order,
        Integer pageNumber,
        List<String> sectionPath,
        Map<String, Object> metadata
) {

    public ParsedBlock {
        type = type == null ? ParsedBlockType.UNKNOWN : type;
        text = text == null ? "" : text;
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
