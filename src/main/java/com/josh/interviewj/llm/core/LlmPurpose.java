package com.josh.interviewj.llm.core;

/**
 * Enumerates supported LLM routing purposes.
 */
public enum LlmPurpose {
    PARSE("parse"),
    ANALYSIS("analysis"),
    RAG("rag"),
    INTERVIEW_QUESTION_GENERATION("interview_question_generation"),
    INTERVIEW_FOLLOW_UP_GENERATION("interview_follow_up_generation"),
    INTERVIEW_ANSWER_EVALUATION("interview_answer_evaluation"),
    INTERVIEW_REPORT_GENERATION("interview_report_generation");

    private final String key;

    LlmPurpose(String key) {
        this.key = key;
    }

    /**
     * Returns the configuration key used to resolve routing for this purpose.
     *
     * @return routing key
     */
    public String key() {
        return key;
    }
}
