package com.josh.interviewj.interview.dto.response;

import com.josh.interviewj.interview.support.InterviewProgressSnapshot;

import java.time.LocalDateTime;
import java.util.UUID;

public record InterviewStartResponse(
        UUID interviewId,
        UUID chatSessionId,
        String status,
        String reportStatus,
        LocalDateTime startTime,
        Integer mainQuestionCount,
        InterviewQuestionItemResponse currentQuestion,
        InterviewProgressSnapshot questionProgress
) {
}
