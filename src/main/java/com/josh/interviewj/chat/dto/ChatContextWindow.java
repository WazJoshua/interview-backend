package com.josh.interviewj.chat.dto;

import java.util.List;

public record ChatContextWindow(
        List<ChatMessageView> messages,
        boolean truncated,
        int returnedCount,
        long totalMessageCount
) {
}
