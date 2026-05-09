package com.josh.interviewj.llm.core;

public record EmbeddingRequest(
        String purpose,
        String input
) {

    public EmbeddingRequest {
        if (purpose != null) {
            purpose = purpose.trim();
            if (purpose.isEmpty()) {
                purpose = null;
            }
        }
    }
}
