package com.josh.interviewj.interview.llm.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Fixed envelope for interview answer evaluation.
 *
 * <p>Provides a consistent structure for both LLM and heuristic evaluation results.</p>
 */
public record InterviewEvaluationEnvelope(
        String schemaVersion,
        String mode,
        boolean fallbackUsed,
        Rubric rubric,
        Integer communication,
        String overallComment,
        List<String> evidence,
        List<String> risks
) {
    public static final String SCHEMA_VERSION = "interview-eval-v1";

    public record Rubric(
            Integer answerRelevance,
            Integer specificity,
            Integer reasoning,
            Integer technicalJudgment,
            Integer communication
    ) {
    }

    /**
     * Create an envelope from LLM rubric payload.
     *
     * @param rubric LLM rubric payload
     * @return evaluation envelope
     */
    public static InterviewEvaluationEnvelope fromRubric(InterviewEvaluationRubricPayload rubric) {
        return new InterviewEvaluationEnvelope(
                SCHEMA_VERSION,
                "llm",
                false,
                new Rubric(
                        rubric.answerRelevance(),
                        rubric.specificity(),
                        rubric.reasoning(),
                        rubric.technicalJudgment(),
                        rubric.communication()
                ),
                rubric.communication(),
                rubric.overallComment(),
                List.copyOf(rubric.evidence()),
                List.copyOf(rubric.risks())
        );
    }

    /**
     * Create an envelope for heuristic fallback.
     *
     * @param overallComment overall comment
     * @return evaluation envelope
     */
    public static InterviewEvaluationEnvelope heuristicFallback(String overallComment) {
        return new InterviewEvaluationEnvelope(
                SCHEMA_VERSION,
                "heuristic_fallback",
                true,
                new Rubric(null, null, null, null, null),
                null,
                overallComment,
                List.of(),
                List.of()
        );
    }

    public static InterviewEvaluationEnvelope legacyCompat(String overallComment) {
        return new InterviewEvaluationEnvelope(
                SCHEMA_VERSION,
                "legacy_compat",
                true,
                new Rubric(null, null, null, null, null),
                null,
                overallComment,
                List.of(),
                List.of()
        );
    }

    /**
     * Calculate weighted evaluation score from rubric scores.
     *
     * <p>Weights: clarity 20%, depth 25%, relevance 20%, accuracy 20%, communication 15%</p>
     *
     * @return weighted score (0-100)
     */
    public BigDecimal calculateWeightedScore() {
        if (fallbackUsed || rubric == null) {
            return null;
        }
        if (rubric.answerRelevance() == null
                || rubric.specificity() == null
                || rubric.reasoning() == null
                || rubric.technicalJudgment() == null
                || rubric.communication() == null) {
            return null;
        }

        int weightedTotal = rubric.answerRelevance() * 30
                + rubric.specificity() * 20
                + rubric.reasoning() * 20
                + rubric.technicalJudgment() * 20
                + rubric.communication() * 10;
        return BigDecimal.valueOf(weightedTotal)
                .divide(BigDecimal.valueOf(5), 2, RoundingMode.HALF_UP);
    }
}
