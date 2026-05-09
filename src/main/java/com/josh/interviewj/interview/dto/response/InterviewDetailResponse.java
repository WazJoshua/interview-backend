package com.josh.interviewj.interview.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.josh.interviewj.interview.support.InterviewProgressSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record InterviewDetailResponse(
        UUID interviewId,
        UUID chatSessionId,
        UUID resumeId,
        String jobTitle,
        String jobDescription,
        String difficultyLevel,
        Integer durationMinutes,
        String interviewMode,
        String status,
        String reportStatus,
        LocalDateTime startTime,
        LocalDateTime endTime,
        InterviewProgressSnapshot questionProgress,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal runningScore,
        String completionReason,
        LocalDateTime createdAt
) {
}
