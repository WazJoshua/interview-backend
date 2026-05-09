package com.josh.interviewj.chat.dto;

import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;

public record ChatMessageView(
        UUID messageId,
        ChatRole role,
        ChatMessageType messageType,
        String content,
        JsonNode metadata,
        LocalDateTime createdAt
) {
}
