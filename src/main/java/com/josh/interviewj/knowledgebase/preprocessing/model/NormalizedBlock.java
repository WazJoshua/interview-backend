package com.josh.interviewj.knowledgebase.preprocessing.model;

import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder(toBuilder = true)
public record NormalizedBlock(
        NormalizedBlockType type,
        String text,
        int order,
        Integer pageNumber,
        List<String> sectionPath,
        Map<String, Object> metadata
) {

    public NormalizedBlock {
        type = type == null ? NormalizedBlockType.UNKNOWN : type;
        text = text == null ? "" : text;
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static NormalizedBlock fromParsedBlock(ParsedBlock block) {
        return NormalizedBlock.builder()
                .type(NormalizedBlockType.fromParsedType(block.type()))
                .text(block.text())
                .order(block.order())
                .pageNumber(block.pageNumber())
                .sectionPath(block.sectionPath())
                .metadata(block.metadata())
                .build();
    }
}
