package com.josh.interviewj.knowledgebase.preprocessing.model;

public enum NormalizedBlockType {
    TITLE,
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TABLE,
    CODE,
    QUOTE,
    HEADER,
    FOOTER,
    UNKNOWN;

    public static NormalizedBlockType fromParsedType(ParsedBlockType type) {
        return type == null ? UNKNOWN : valueOf(type.name());
    }
}
