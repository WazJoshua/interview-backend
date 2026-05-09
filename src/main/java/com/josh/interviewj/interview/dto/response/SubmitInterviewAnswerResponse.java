package com.josh.interviewj.interview.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

public record SubmitInterviewAnswerResponse(
        UUID interviewId,
        UUID chatSessionId,
        UUID questionId,
        UUID answerId,
        UUID userMessageId,
        UUID evaluationMessageId,
        BigDecimal evaluationScore,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal branchScore,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal runningScore,
        Integer answeredMainQuestionCount,
        Integer remainingMainQuestionCount,
        String nextAction,
        FollowUpQuestionResponse followUpQuestion,
        String reportStatus
) {

    public record FollowUpQuestionResponse(
            UUID questionId,
            String questionKind,
            String followUpIntent,
            UUID parentQuestionId,
            Integer branchDepth,
            String questionContent
    ) {
    }
}
