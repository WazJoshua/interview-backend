package com.josh.interviewj.ragqa.dto.response;

import com.josh.interviewj.chat.model.ChatSessionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record KnowledgeBaseChatSessionItemResponse(
        UUID chatSessionId,
        String title,
        String lastMessagePreview,
        Integer messageCount,
        ChatSessionStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
