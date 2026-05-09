package com.josh.interviewj.interview.llm.dto;

/**
 * Payload for LLM-generated evaluation rubric.
 */
public record InterviewEvaluationRubricPayload(
        Integer answerRelevance,
        Integer specificity,
        Integer reasoning,
        Integer technicalJudgment,
        Integer communication,
        String overallComment,
        java.util.List<String> evidence,
        java.util.List<String> risks
) {
    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 5;

    public boolean isValid() {
        return isValidScore(answerRelevance)
                && isValidScore(specificity)
                && isValidScore(reasoning)
                && isValidScore(technicalJudgment)
                && isValidScore(communication)
                && overallComment != null && !overallComment.isBlank()
                && evidence != null
                && risks != null;
    }

    private boolean isValidScore(Integer score) {
        return score != null && score >= MIN_SCORE && score <= MAX_SCORE;
    }
}
