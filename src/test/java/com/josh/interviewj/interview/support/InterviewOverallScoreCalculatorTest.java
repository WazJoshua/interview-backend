package com.josh.interviewj.interview.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterviewOverallScoreCalculatorTest {

    private final InterviewOverallScoreCalculator calculator = new InterviewOverallScoreCalculator();

    @Test
    void calculateOverallScore_NullRunningScore_ReturnsNull() {
        BigDecimal result = calculator.calculateOverallScore(
                null, "COMPLETED_ALL", 10, 10
        );

        assertNull(result);
    }

    @Test
    void calculateOverallScore_Completed_NoPenalty() {
        BigDecimal result = calculator.calculateOverallScore(
                BigDecimal.valueOf(80), "COMPLETED_ALL", 10, 10
        );

        assertNotNull(result);
        assertEquals(BigDecimal.valueOf(80.00).setScale(2), result);
    }

    @Test
    void calculateOverallScore_EarlyEnd_AppliesPenalty() {
        BigDecimal result = calculator.calculateOverallScore(
                BigDecimal.valueOf(80), "USER_EARLY_END", 8, 10
        );

        assertNotNull(result);
        // Early end penalty (5) + incompletion penalty (2) = 7
        assertEquals(BigDecimal.valueOf(73.00).setScale(2), result);
    }

    @Test
    void calculateOverallScore_Aborted_AppliesPenalty() {
        BigDecimal result = calculator.calculateOverallScore(
                BigDecimal.valueOf(80), "ABORTED", 6, 10
        );

        assertNotNull(result);
        // Early end penalty (10) + incompletion penalty (4) = 14
        assertEquals(BigDecimal.valueOf(66.00).setScale(2), result);
    }

    @Test
    void calculateOverallScore_PartialCompletion_AppliesIncompletionPenalty() {
        BigDecimal result = calculator.calculateOverallScore(
                BigDecimal.valueOf(80), "COMPLETED_ALL", 7, 10
        );

        assertNotNull(result);
        // Only incompletion penalty (3)
        assertEquals(BigDecimal.valueOf(77.00).setScale(2), result);
    }

    @Test
    void calculateOverallScore_LowScore_ClampedAtZero() {
        BigDecimal result = calculator.calculateOverallScore(
                BigDecimal.valueOf(5), "USER_EARLY_END", 2, 10
        );

        assertNotNull(result);
        assertEquals(BigDecimal.valueOf(0.00).setScale(2), result);
    }

    @Test
    void calculateOverallScore_HighScore_ClampedAt100() {
        BigDecimal result = calculator.calculateOverallScore(
                BigDecimal.valueOf(99), "COMPLETED_ALL", 10, 10
        );

        assertNotNull(result);
        assertEquals(BigDecimal.valueOf(99.00).setScale(2), result);
    }

    @Test
    void calculateEarlyEndPenalty_CompletedAll_ReturnsZero() {
        double penalty = calculator.calculateEarlyEndPenalty("COMPLETED_ALL");

        assertEquals(0, penalty);
    }

    @Test
    void calculateEarlyEndPenalty_UserEarlyEnd_ReturnsPenalty() {
        double penalty = calculator.calculateEarlyEndPenalty("USER_EARLY_END");

        assertEquals(5, penalty);
    }

    @Test
    void calculateEarlyEndPenalty_Aborted_ReturnsPenalty() {
        double penalty = calculator.calculateEarlyEndPenalty("ABORTED");

        assertEquals(10, penalty);
    }

    @Test
    void calculateIncompletionPenalty_AllCompleted_ReturnsZero() {
        double penalty = calculator.calculateIncompletionPenalty(10, 10);

        assertEquals(0, penalty);
    }

    @Test
    void calculateIncompletionPenalty_HalfCompleted_ReturnsPenalty() {
        double penalty = calculator.calculateIncompletionPenalty(5, 10);

        // 50% incomplete, 0.5 * 10 = 5
        assertEquals(5.0, penalty);
    }

    @Test
    void calculateIncompletionPenalty_NoneCompleted_ReturnsMaxPenalty() {
        double penalty = calculator.calculateIncompletionPenalty(0, 10);

        // 100% incomplete, 1.0 * 15 = 15, capped at 10
        assertEquals(10, penalty);
    }

    @Test
    void calculateInstabilityPenalty_SingleScore_ReturnsZero() {
        double penalty = calculator.calculateInstabilityPenalty(
                List.of(BigDecimal.valueOf(80))
        );

        assertEquals(0, penalty);
    }

    @Test
    void calculateInstabilityPenalty_ConsistentScores_ReturnsLowPenalty() {
        double penalty = calculator.calculateInstabilityPenalty(
                List.of(BigDecimal.valueOf(80), BigDecimal.valueOf(82), BigDecimal.valueOf(78))
        );

        assertEquals(0.0, penalty);
    }

    @Test
    void calculateInstabilityPenalty_VariableScores_ReturnsHigherPenalty() {
        double penalty = calculator.calculateInstabilityPenalty(
                List.of(BigDecimal.valueOf(60), BigDecimal.valueOf(90), BigDecimal.valueOf(50))
        );

        assertEquals(4.0, penalty);
    }

    @Test
    void calculateInstabilityPenalty_LessThanThreeBranches_ReturnsZero() {
        double penalty = calculator.calculateInstabilityPenalty(
                List.of(BigDecimal.valueOf(60), BigDecimal.valueOf(90))
        );

        assertEquals(0.0, penalty);
    }

    @Test
    void calculateWithPenalties_ReturnsBreakdown() {
        InterviewOverallScoreCalculator.OverallScoreResult result = calculator.calculateWithPenalties(
                BigDecimal.valueOf(80),
                "USER_EARLY_END",
                8,
                10,
                List.of(BigDecimal.valueOf(70), BigDecimal.valueOf(90))
        );

        assertNotNull(result);
        assertNotNull(result.overallScore());
        assertEquals(5, result.earlyEndPenalty());
        assertTrue(result.incompletionPenalty() > 0);
        assertTrue(result.totalPenalty() > 0);
    }
}
