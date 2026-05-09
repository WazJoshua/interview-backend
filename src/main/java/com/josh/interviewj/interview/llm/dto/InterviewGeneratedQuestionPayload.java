package com.josh.interviewj.interview.llm.dto;

/**
 * Payload for LLM-generated interview question.
 */
public record InterviewGeneratedQuestionPayload(
        Integer sequenceNumber,
        String questionContent,
        String focusHint
) {
    public boolean isValid() {
        return sequenceNumber != null && sequenceNumber > 0
                && questionContent != null && !questionContent.isBlank();
    }
}
