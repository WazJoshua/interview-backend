package com.josh.interviewj.interview.support;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculator for overall interview score with penalty adjustments.
 *
 * <p>Calculates the final overall score based on running score and various penalties:</p>
 * <ul>
 *   <li>Early end penalty - when interview ends before completing all questions</li>
 *   <li>Incompletion penalty - when not all main questions are answered</li>
 *   <li>Instability penalty - based on score variance across answers</li>
 * </ul>
 */
@Component
public class InterviewOverallScoreCalculator {

    /**
     * Calculate the overall score with penalties applied.
     *
     * @param runningScore the running score (average of finalized branch scores)
     * @param completionReason the reason for interview completion (e.g., "COMPLETED_ALL", "USER_EARLY_END", "ABORTED")
     * @param completedMainQuestionCount number of main questions answered
     * @param mainQuestionCount total number of main questions
     * @return overall score with penalties applied, or null if runningScore is null
     */
    public BigDecimal calculateOverallScore(
            BigDecimal runningScore,
            String completionReason,
            int completedMainQuestionCount,
            int mainQuestionCount
    ) {
        if (runningScore == null) {
            return null;
        }

        double baseScore = runningScore.doubleValue();

        // Apply early end penalty
        double earlyEndPenalty = calculateEarlyEndPenalty(completionReason);
        baseScore -= earlyEndPenalty;

        // Apply incompletion penalty
        double incompletionPenalty = calculateIncompletionPenalty(
                completedMainQuestionCount, mainQuestionCount);
        baseScore -= incompletionPenalty;

        // Clamp to valid range
        double finalScore = Math.max(0, Math.min(100, baseScore));

        return BigDecimal.valueOf(finalScore).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate early end penalty.
     *
     * <p>Returns a penalty value based on the completion reason.</p>
     *
     * @param completionReason the reason for completion
     * @return penalty value (0 for normal completion, positive for early end)
     */
    public double calculateEarlyEndPenalty(String completionReason) {
        if (completionReason == null) {
            return 0;
        }

        return switch (completionReason) {
            case "COMPLETED_ALL" -> 0;
            case "USER_EARLY_END" -> 5;
            case "ABORTED" -> 10;
            default -> 0;
        };
    }

    /**
     * Calculate incompletion penalty based on unanswered questions.
     *
     * <p>The penalty is proportional to the percentage of unanswered questions.</p>
     *
     * @param completedCount number of completed main questions
     * @param totalCount total number of main questions
     * @return penalty value
     */
    public double calculateIncompletionPenalty(int completedCount, int totalCount) {
        if (totalCount <= 0 || completedCount >= totalCount) {
            return 0;
        }

        double incompletionRate = 1 - ((double) completedCount / totalCount);
        double penalty = BigDecimal.valueOf(incompletionRate * 10)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
        return Math.max(0, Math.min(10, penalty));
    }

    /**
     * Calculate instability penalty based on score variance.
     *
     * <p>Higher variance in scores indicates inconsistency, resulting in higher penalty.</p>
     *
     * @param branchScores list of finalized branch scores
     * @return penalty value
     */
    public double calculateInstabilityPenalty(java.util.List<BigDecimal> branchScores) {
        if (branchScores == null || branchScores.size() < 3) {
            return 0;
        }

        double max = branchScores.stream().mapToDouble(BigDecimal::doubleValue).max().orElse(0);
        double min = branchScores.stream().mapToDouble(BigDecimal::doubleValue).min().orElse(0);
        double range = max - min;
        if (range < 15) {
            return 0;
        }
        if (range < 30) {
            return 2;
        }
        return 4;
    }

    /**
     * Result containing overall score and penalty breakdown.
     */
    public record OverallScoreResult(
            BigDecimal overallScore,
            double earlyEndPenalty,
            double incompletionPenalty,
            double instabilityPenalty
    ) {
        public double totalPenalty() {
            return earlyEndPenalty + incompletionPenalty + instabilityPenalty;
        }
    }

    /**
     * Calculate overall score with detailed penalty breakdown.
     *
     * @param runningScore the running score
     * @param completionReason the completion reason
     * @param completedMainQuestionCount number of completed main questions
     * @param mainQuestionCount total main questions
     * @param branchScores list of branch scores for instability calculation
     * @return result with overall score and penalties
     */
    public OverallScoreResult calculateWithPenalties(
            BigDecimal runningScore,
            String completionReason,
            int completedMainQuestionCount,
            int mainQuestionCount,
            java.util.List<BigDecimal> branchScores
    ) {
        if (runningScore == null) {
            return new OverallScoreResult(null, 0, 0, 0);
        }

        double earlyEndPenalty = calculateEarlyEndPenalty(completionReason);
        double incompletionPenalty = calculateIncompletionPenalty(
                completedMainQuestionCount, mainQuestionCount);
        double instabilityPenalty = calculateInstabilityPenalty(branchScores);

        double baseScore = runningScore.doubleValue();
        double totalPenalty = earlyEndPenalty + incompletionPenalty + instabilityPenalty;
        double finalScore = Math.max(0, Math.min(100, baseScore - totalPenalty));

        return new OverallScoreResult(
                BigDecimal.valueOf(finalScore).setScale(2, RoundingMode.HALF_UP),
                earlyEndPenalty,
                incompletionPenalty,
                instabilityPenalty
        );
    }
}
