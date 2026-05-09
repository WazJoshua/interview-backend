package com.josh.interviewj.interview.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record InterviewQuestionItemResponse(
        UUID questionId,
        String questionKind,
        String followUpIntent,
        UUID parentQuestionId,
        Integer branchDepth,
        Integer sequenceNumber,
        String questionType,
        String questionContent,
        Integer difficulty,
        Integer estimatedMinutes,
        AnswerProjection answer
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnswerProjection(
            UUID answerId,
            String answerContent,
            Integer durationSeconds,
            BigDecimal evaluationScore,
            Map<String, Object> evaluationDetails
    ) {
    }
}
