package com.josh.interviewj.chat.dto;

import com.josh.interviewj.chat.model.ChatSessionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record ChatSessionSummaryView(
        UUID chatSessionId,
        String title,
        String lastMessagePreview,
        Integer messageCount,
        ChatSessionStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
