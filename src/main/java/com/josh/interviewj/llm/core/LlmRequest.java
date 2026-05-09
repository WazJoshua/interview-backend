package com.josh.interviewj.llm.core;

public record LlmRequest(
        String purpose,
        String systemPrompt,
        String userPrompt
) {

    public LlmRequest {
        if (purpose != null) {
            purpose = purpose.trim();
            if (purpose.isEmpty()) {
                purpose = null;
            }
        }
    }

    public LlmRequest(LlmPurpose purpose, String systemPrompt, String userPrompt) {
        this(purpose == null ? null : purpose.key(), systemPrompt, userPrompt);
    }
}
