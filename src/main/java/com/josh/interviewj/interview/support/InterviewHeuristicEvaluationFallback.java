package com.josh.interviewj.interview.support;

import com.josh.interviewj.interview.llm.dto.InterviewEvaluationEnvelope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Heuristic fallback for interview answer evaluation.
 *
 * <p>Used when LLM evaluation is not available or fails.
 * Provides deterministic scoring based on answer characteristics.</p>
 */
@Component
public class InterviewHeuristicEvaluationFallback {

    /**
     * Evaluate an answer using heuristic rules.
     *
     * @param questionContent the question content
     * @param answerContent the answer content
     * @param durationSeconds answer duration in seconds
     * @param jobTitle job title for context
     * @return evaluation envelope with heuristic score
     */
    public InterviewEvaluationEnvelope evaluate(
            String questionContent,
            String answerContent,
            Integer durationSeconds,
            String jobTitle
    ) {
        BigDecimal score = heuristicScore(answerContent, durationSeconds);
        String comment = summaryFor(score);
        return InterviewEvaluationEnvelope.heuristicFallback(comment);
    }

    /**
     * Calculate heuristic score only (for InterviewEvaluationService compatibility).
     *
     * @param answerContent the answer content
     * @param durationSeconds answer duration in seconds
     * @return heuristic score
     */
    public BigDecimal calculateScore(String answerContent, Integer durationSeconds) {
        return heuristicScore(answerContent, durationSeconds);
    }

    private BigDecimal heuristicScore(String answerContent, Integer durationSeconds) {
        String content = answerContent == null ? "" : answerContent.trim();
        int lengthScore = Math.min(45, content.length() / 8);
        int structureBonus = hasStructure(content) ? 10 : 0;
        int detailBonus = hasDetail(content) ? 8 : 0;
        int durationBonus = durationSeconds == null ? 0 : Math.min(7, durationSeconds / 30);
        return BigDecimal.valueOf(Math.min(100, 35 + lengthScore + structureBonus + detailBonus + durationBonus))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private boolean hasStructure(String content) {
        String lower = content.toLowerCase();
        return lower.contains("first") || lower.contains("首先")
                || lower.contains("second") || lower.contains("其次")
                || lower.contains("finally") || lower.contains("最后")
                || lower.contains("1.") || lower.contains("2.");
    }

    private boolean hasDetail(String content) {
        String lower = content.toLowerCase();
        return lower.contains("because") || lower.contains("因为")
                || lower.contains("therefore") || lower.contains("所以")
                || lower.contains("for example") || lower.contains("例如")
                || lower.contains("specifically") || lower.contains("具体");
    }

    private String summaryFor(BigDecimal score) {
        double value = score.doubleValue();
        if (value < 60) {
            return "Answer needs more concrete detail.";
        }
        if (value < 80) {
            return "Answer is solid but still has room for depth.";
        }
        return "Answer is strong and shows clear depth.";
    }
}