package com.josh.interviewj.interview.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record InterviewListItemResponse(
        UUID interviewId,
        UUID chatSessionId,
        String jobTitle,
        String difficultyLevel,
        String interviewMode,
        String status,
        String reportStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
