package com.josh.interviewj.interview.service;

import com.josh.interviewj.interview.config.InterviewScoringProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewScoringServiceTest {

    private InterviewScoringService interviewScoringService;

    @BeforeEach
    void setUp() {
        interviewScoringService = new InterviewScoringService(new InterviewScoringProperties());
    }

    @Test
    void determineRoute_UsesLockedSignalBoundaries() {
        assertEquals("CLARIFY", interviewScoringService.determineRoute(BigDecimal.valueOf(59)).name());
        assertEquals("NEXT_MAIN_QUESTION", interviewScoringService.determineRoute(BigDecimal.valueOf(60)).name());
        assertEquals("NEXT_MAIN_QUESTION", interviewScoringService.determineRoute(BigDecimal.valueOf(79)).name());
        assertEquals("DEEP_DIVE", interviewScoringService.determineRoute(BigDecimal.valueOf(80)).name());
    }

    @Test
    void calculateRunningScore_ReturnsNullWhenNoFinalizedBranchExists() {
        assertNull(interviewScoringService.calculateRunningScore(List.of()));
    }

    @Test
    void calculateRunningScore_AveragesFinalizedBranchesWithHalfUpRounding() {
        assertEquals(BigDecimal.valueOf(86.67), interviewScoringService.calculateRunningScore(List.of(
                BigDecimal.valueOf(84),
                BigDecimal.valueOf(88),
                BigDecimal.valueOf(88)
        )));
    }

    @Test
    void clarifyAdjustment_StaysContinuousAcross59And60() {
        BigDecimal score59 = interviewScoringService.finalizeClarifyBranch(
                BigDecimal.valueOf(59),
                BigDecimal.valueOf(60)
        );
        BigDecimal score60 = interviewScoringService.finalizeClarifyBranch(
                BigDecimal.valueOf(60),
                BigDecimal.valueOf(60)
        );

        assertTrue(score60.subtract(score59).abs().doubleValue() < 2.0);
    }

    @Test
    void deepDiveBonus_UsesSoftGateWithoutCliffBetween79And80() {
        BigDecimal score79 = interviewScoringService.finalizeDeepDiveBranch(
                BigDecimal.valueOf(79),
                List.of(BigDecimal.valueOf(80))
        );
        BigDecimal score80 = interviewScoringService.finalizeDeepDiveBranch(
                BigDecimal.valueOf(80),
                List.of(BigDecimal.valueOf(80))
        );

        assertTrue(score80.subtract(score79).abs().doubleValue() < 3.0);
    }
}
