package com.josh.interviewj.chat.dto;

import java.util.UUID;

public record ChatTurnWriteResult(
        UUID chatSessionId,
        UUID userMessageId,
        UUID assistantMessageId
) {
}
