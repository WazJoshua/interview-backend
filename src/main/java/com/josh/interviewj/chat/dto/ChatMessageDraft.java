package com.josh.interviewj.chat.dto;

import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;

public record ChatMessageDraft(
        ChatRole role,
        ChatMessageType messageType,
        String content,
        String metadata,
        Integer estimatedTokenCount,
        Long anchorMessageId
) {
}
