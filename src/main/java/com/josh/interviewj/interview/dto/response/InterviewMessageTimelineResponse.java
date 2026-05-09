package com.josh.interviewj.interview.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record InterviewMessageTimelineResponse(
        UUID interviewId,
        UUID chatSessionId,
        List<MessageItem> messages,
        boolean truncated,
        int returnedCount,
        long totalMessageCount
) {

    public record MessageItem(
            UUID messageId,
            String role,
            String messageType,
            String content,
            Map<String, Object> metadata,
            LocalDateTime createdAt
    ) {
    }
}
