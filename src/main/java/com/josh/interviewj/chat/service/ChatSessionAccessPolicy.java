package com.josh.interviewj.chat.service;

import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class ChatSessionAccessPolicy {

    public boolean canRead(ChatSessionStatus status) {
        return status != null;
    }

    public boolean canWrite(ChatSessionStatus status) {
        return status == ChatSessionStatus.ACTIVE || status == ChatSessionStatus.CREATED;
    }

    public void assertReadable(ChatSession session) {
        if (!canRead(session.getStatus())) {
            throw new BusinessException(ErrorCode.KB_003, "Session is not readable");
        }
    }

    public void assertWritable(ChatSession session) {
        if (!canWrite(session.getStatus())) {
            throw new BusinessException(ErrorCode.KB_003, "Session is not writable");
        }
    }
}
