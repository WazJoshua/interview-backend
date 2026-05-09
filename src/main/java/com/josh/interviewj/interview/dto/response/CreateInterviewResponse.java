package com.josh.interviewj.interview.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record CreateInterviewResponse(
        UUID interviewId,
        UUID chatSessionId,
        UUID resumeId,
        String jobTitle,
        String difficultyLevel,
        Integer durationMinutes,
        String interviewMode,
        String status,
        String reportStatus,
        LocalDateTime createdAt
) {
}
