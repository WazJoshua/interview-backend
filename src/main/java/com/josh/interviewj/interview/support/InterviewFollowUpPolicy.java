package com.josh.interviewj.interview.support;

import com.josh.interviewj.interview.model.InterviewFollowUpIntent;
import com.josh.interviewj.interview.service.InterviewScoringService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class InterviewFollowUpPolicy {

    public static final int MAX_FOLLOW_UP_DEPTH_PER_MAIN = 3;
    public static final int MAX_FOLLOW_UPS_PER_SESSION = 6;

    private final InterviewScoringService interviewScoringService;

    public InterviewFollowUpPolicy(InterviewScoringService interviewScoringService) {
        this.interviewScoringService = interviewScoringService;
    }

    public Decision decideForMainQuestion(BigDecimal evaluationScore, int usedFollowUpCount) {
        InterviewScoringService.FollowUpRoute route = interviewScoringService.determineRoute(evaluationScore);
        if (route == InterviewScoringService.FollowUpRoute.NEXT_MAIN_QUESTION || usedFollowUpCount >= MAX_FOLLOW_UPS_PER_SESSION) {
            return new Decision(NextAction.NEXT_MAIN_QUESTION, null, 0);
        }
        if (route == InterviewScoringService.FollowUpRoute.CLARIFY) {
            return new Decision(NextAction.FOLLOW_UP, InterviewFollowUpIntent.CLARIFY, 1);
        }
        return new Decision(NextAction.FOLLOW_UP, InterviewFollowUpIntent.DEEP_DIVE, 1);
    }

    public Decision decideForFollowUpQuestion(
            InterviewFollowUpIntent currentIntent,
            BigDecimal evaluationScore,
            int currentDepth,
            int usedFollowUpCount
    ) {
        if (currentIntent == InterviewFollowUpIntent.CLARIFY) {
            return new Decision(NextAction.NEXT_MAIN_QUESTION, null, 0);
        }
        boolean canContinue = currentDepth < MAX_FOLLOW_UP_DEPTH_PER_MAIN
                && usedFollowUpCount < MAX_FOLLOW_UPS_PER_SESSION
                && interviewScoringService.determineRoute(evaluationScore) == InterviewScoringService.FollowUpRoute.DEEP_DIVE;
        if (!canContinue) {
            return new Decision(NextAction.NEXT_MAIN_QUESTION, null, 0);
        }
        return new Decision(NextAction.FOLLOW_UP, InterviewFollowUpIntent.DEEP_DIVE, currentDepth + 1);
    }

    public enum NextAction {
        FOLLOW_UP,
        NEXT_MAIN_QUESTION,
        INTERVIEW_COMPLETABLE
    }

    public record Decision(
            NextAction nextAction,
            InterviewFollowUpIntent followUpIntent,
            int nextDepth
    ) {
    }
}
