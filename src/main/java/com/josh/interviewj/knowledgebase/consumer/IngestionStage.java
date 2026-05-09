package com.josh.interviewj.knowledgebase.consumer;

public enum IngestionStage {
    UNKNOWN,
    PREPROCESS,
    ARTIFACT_PERSIST,
    CHUNK_PLAN,
    CHUNK_PERSIST,
    EMBED;

    public static IngestionStage fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return IngestionStage.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
