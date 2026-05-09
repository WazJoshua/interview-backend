package com.josh.interviewj.interview.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record InterviewLifecycleResponse(
        UUID interviewId,
        UUID chatSessionId,
        String status,
        String completionReason,
        String reportStatus,
        LocalDateTime endTime
) {
}
