package com.josh.interviewj.chat.service;

import com.josh.interviewj.chat.model.ChatSessionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionAccessPolicyTest {

    private final ChatSessionAccessPolicy chatSessionAccessPolicy = new ChatSessionAccessPolicy();

    @Test
    void archivedSession_IsReadableButNotWritable() {
        assertTrue(chatSessionAccessPolicy.canRead(ChatSessionStatus.ARCHIVED));
        assertFalse(chatSessionAccessPolicy.canWrite(ChatSessionStatus.ARCHIVED));
    }

    @Test
    void activeSession_IsReadableAndWritable() {
        assertTrue(chatSessionAccessPolicy.canRead(ChatSessionStatus.ACTIVE));
        assertTrue(chatSessionAccessPolicy.canWrite(ChatSessionStatus.ACTIVE));
    }
}
