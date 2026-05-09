package com.josh.interviewj.interview.support;

import com.josh.interviewj.interview.config.InterviewScoringProperties;
import com.josh.interviewj.interview.model.InterviewFollowUpIntent;
import com.josh.interviewj.interview.service.InterviewScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterviewFollowUpPolicyTest {

    private InterviewFollowUpPolicy interviewFollowUpPolicy;

    @BeforeEach
    void setUp() {
        interviewFollowUpPolicy = new InterviewFollowUpPolicy(new InterviewScoringService(new InterviewScoringProperties()));
    }

    @Test
    void decideForMainQuestion_HighSignalCreatesDeepDiveFollowUp() {
        InterviewFollowUpPolicy.Decision decision = interviewFollowUpPolicy.decideForMainQuestion(
                BigDecimal.valueOf(80),
                0
        );

        assertEquals(InterviewFollowUpPolicy.NextAction.FOLLOW_UP, decision.nextAction());
        assertEquals(InterviewFollowUpIntent.DEEP_DIVE, decision.followUpIntent());
        assertEquals(1, decision.nextDepth());
    }

    @Test
    void decideForFollowUpQuestion_ClarifyNeverCreatesAnotherFollowUp() {
        InterviewFollowUpPolicy.Decision decision = interviewFollowUpPolicy.decideForFollowUpQuestion(
                InterviewFollowUpIntent.CLARIFY,
                BigDecimal.valueOf(95),
                1,
                1
        );

        assertEquals(InterviewFollowUpPolicy.NextAction.NEXT_MAIN_QUESTION, decision.nextAction());
        assertEquals(null, decision.followUpIntent());
    }

    @Test
    void decideForFollowUpQuestion_DeepDiveStopsAtMaxDepthAndBudget() {
        InterviewFollowUpPolicy.Decision depthDecision = interviewFollowUpPolicy.decideForFollowUpQuestion(
                InterviewFollowUpIntent.DEEP_DIVE,
                BigDecimal.valueOf(88),
                InterviewFollowUpPolicy.MAX_FOLLOW_UP_DEPTH_PER_MAIN,
                1
        );
        InterviewFollowUpPolicy.Decision budgetDecision = interviewFollowUpPolicy.decideForFollowUpQuestion(
                InterviewFollowUpIntent.DEEP_DIVE,
                BigDecimal.valueOf(88),
                2,
                InterviewFollowUpPolicy.MAX_FOLLOW_UPS_PER_SESSION
        );

        assertEquals(InterviewFollowUpPolicy.NextAction.NEXT_MAIN_QUESTION, depthDecision.nextAction());
        assertEquals(InterviewFollowUpPolicy.NextAction.NEXT_MAIN_QUESTION, budgetDecision.nextAction());
    }
}
