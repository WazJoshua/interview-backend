package com.josh.interviewj.interview.service;

import com.josh.interviewj.interview.config.InterviewScoringProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Service for calculating interview scores.
 *
 * <p>Supports configurable deep-dive bonus calculation modes:</p>
 * <ul>
 *   <li>{@code sigmoid} - Sigmoid-based bonus calculation (default)</li>
 *   <li>{@code linear} - Linear bonus calculation</li>
 * </ul>
 */
@Service
public class InterviewScoringService {

    private final InterviewScoringProperties properties;

    private static final BigDecimal CLARIFY_CAP = BigDecimal.valueOf(4);
    private static final BigDecimal[] BONUS_CAPS = {
            BigDecimal.valueOf(3),
            BigDecimal.valueOf(4),
            BigDecimal.valueOf(5)
    };
    private static final double[] THETA = {60.0, 68.0, 75.0};
    private static final double[] K = {0.22, 0.24, 0.26};

    /**
     * Constructor with configuration properties.
     *
     * @param properties scoring configuration properties
     */
    public InterviewScoringService(InterviewScoringProperties properties) {
        this.properties = properties != null ? properties : new InterviewScoringProperties();
    }

    public FollowUpRoute determineRoute(BigDecimal evaluationScore) {
        double score = normalizeScore(evaluationScore).doubleValue();
        if (score < 60) {
            return FollowUpRoute.CLARIFY;
        }
        if (score < 80) {
            return FollowUpRoute.NEXT_MAIN_QUESTION;
        }
        return FollowUpRoute.DEEP_DIVE;
    }

    public BigDecimal finalizeMainOnly(BigDecimal mainScore) {
        return normalizeScore(mainScore);
    }

    public BigDecimal finalizeClarifyBranch(BigDecimal mainScore, BigDecimal clarifyScore) {
        BigDecimal base = normalizeScore(mainScore);
        double clarifySignal = clamp((normalizeScore(clarifyScore).doubleValue() - 60.0) / 40.0, -1.0, 1.0);
        BigDecimal adjustment = CLARIFY_CAP.multiply(BigDecimal.valueOf(clarifySignal));
        return clampScore(base.add(adjustment));
    }

    public BigDecimal finalizeDeepDiveBranch(BigDecimal mainScore, List<BigDecimal> deepDiveScores) {
        if (properties.isLinearMode()) {
            return finalizeDeepDiveBranchLinear(mainScore, deepDiveScores);
        }
        return finalizeDeepDiveBranchSigmoid(mainScore, deepDiveScores);
    }

    /**
     * Sigmoid-based deep-dive bonus calculation (default).
     */
    private BigDecimal finalizeDeepDiveBranchSigmoid(BigDecimal mainScore, List<BigDecimal> deepDiveScores) {
        BigDecimal branchScore = normalizeScore(mainScore);
        double previousScore = branchScore.doubleValue();
        int count = Math.min(BONUS_CAPS.length, deepDiveScores == null ? 0 : deepDiveScores.size());
        for (int index = 0; index < count; index++) {
            BigDecimal deepDiveScore = normalizeScore(deepDiveScores.get(index));
            double quality = clamp(deepDiveScore.doubleValue() / 100.0, 0.0, 1.0);
            double gate = 1.0 / (1.0 + Math.exp(-K[index] * (previousScore - THETA[index])));
            BigDecimal bonus = BONUS_CAPS[index]
                    .multiply(BigDecimal.valueOf(quality))
                    .multiply(BigDecimal.valueOf(gate));
            branchScore = branchScore.add(bonus);
            previousScore = deepDiveScore.doubleValue();
        }
        return clampScore(branchScore);
    }

    /**
     * Linear deep-dive bonus calculation.
     */
    private BigDecimal finalizeDeepDiveBranchLinear(BigDecimal mainScore, List<BigDecimal> deepDiveScores) {
        BigDecimal branchScore = normalizeScore(mainScore);
        int count = Math.min(BONUS_CAPS.length, deepDiveScores == null ? 0 : deepDiveScores.size());
        for (int index = 0; index < count; index++) {
            BigDecimal deepDiveScore = normalizeScore(deepDiveScores.get(index));
            // Linear bonus: quality * bonusCap
            double quality = clamp(deepDiveScore.doubleValue() / 100.0, 0.0, 1.0);
            BigDecimal bonus = BONUS_CAPS[index].multiply(BigDecimal.valueOf(quality));
            branchScore = branchScore.add(bonus);
        }
        return clampScore(branchScore);
    }

    public BigDecimal calculateRunningScore(List<BigDecimal> finalizedBranchScores) {
        if (finalizedBranchScores == null || finalizedBranchScores.isEmpty()) {
            return null;
        }
        BigDecimal sum = finalizedBranchScores.stream()
                .map(this::normalizeScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(finalizedBranchScores.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeScore(BigDecimal score) {
        if (score == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal clampScore(BigDecimal score) {
        return BigDecimal.valueOf(clamp(score.doubleValue(), 0.0, 100.0)).setScale(2, RoundingMode.HALF_UP);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public enum FollowUpRoute {
        CLARIFY,
        NEXT_MAIN_QUESTION,
        DEEP_DIVE
    }
}