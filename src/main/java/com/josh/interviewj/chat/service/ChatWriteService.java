package com.josh.interviewj.chat.service;

import com.josh.interviewj.chat.config.ChatProperties;
import com.josh.interviewj.chat.dto.ChatMessageDraft;
import com.josh.interviewj.chat.dto.ChatTurnWriteResult;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatWriteService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionAccessPolicy chatSessionAccessPolicy;
    private final ChatEventRecorder chatEventRecorder;
    private final ChatProperties chatProperties;

    @Transactional
    public ChatTurnWriteResult appendTurn(
            ChatSession session,
            ChatMessageDraft userMessage,
            ChatMessageDraft assistantMessage
    ) {
        boolean newSession = session.getId() == null;
        ChatSession persistedSession = prepareSession(session);
        ChatSession lockedSession = chatSessionRepository.findByIdForUpdate(persistedSession.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_001, "Chat session not found"));
        chatSessionAccessPolicy.assertWritable(lockedSession);

        int firstSequence = lockedSession.getNextMessageSequence();
        LocalDateTime now = LocalDateTime.now();
        ChatMessage persistedUserMessage = newMessage(lockedSession.getId(), userMessage, firstSequence, now);
        ChatMessage persistedAssistantMessage = newMessage(lockedSession.getId(), assistantMessage, firstSequence + 1, now);
        List<ChatMessage> savedMessages = chatMessageRepository.saveAll(List.of(persistedUserMessage, persistedAssistantMessage));

        lockedSession.setNextMessageSequence(firstSequence + savedMessages.size());
        lockedSession.setMessageCount(lockedSession.getMessageCount() + savedMessages.size());
        lockedSession.setLastMessagePreview(trimPreview(persistedAssistantMessage.getContent()));
        lockedSession.setLastMessageAt(now);
        chatSessionRepository.save(lockedSession);
        recordTurnEvents(newSession, lockedSession, savedMessages);

        return new ChatTurnWriteResult(
                lockedSession.getExternalId(),
                savedMessages.getFirst().getExternalId(),
                savedMessages.getLast().getExternalId()
        );
    }

    private ChatSession prepareSession(ChatSession session) {
        if (session.getId() != null) {
            return session;
        }
        return chatSessionRepository.save(session);
    }

    private ChatMessage newMessage(
            Long chatSessionId,
            ChatMessageDraft draft,
            int sequenceNumber,
            LocalDateTime createdAt
    ) {
        return ChatMessage.builder()
                .chatSessionId(chatSessionId)
                .role(draft.role())
                .messageType(draft.messageType())
                .content(draft.content())
                .metadata(draft.metadata())
                .sequenceNumber(sequenceNumber)
                .anchorMessageId(draft.anchorMessageId())
                .estimatedTokenCount(draft.estimatedTokenCount() == null ? estimateTokens(draft.content()) : draft.estimatedTokenCount())
                .createdAt(createdAt)
                .build();
    }

    private int estimateTokens(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return content.length();
    }

    private String trimPreview(String content) {
        if (content == null) {
            return null;
        }
        int maxPreviewLength = Math.max(0, chatProperties.getMaxPreviewLength());
        if (maxPreviewLength == 0) {
            return "";
        }
        return content.length() <= maxPreviewLength ? content : content.substring(0, maxPreviewLength);
    }

    private void recordTurnEvents(boolean newSession, ChatSession session, List<ChatMessage> savedMessages) {
        if (newSession) {
            chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                    session.getId(),
                    null,
                    session.getDomainType(),
                    "SESSION_CREATED",
                    "SUCCESS",
                    java.util.Map.of("messageCount", session.getMessageCount())
            ));
        }
        chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                session.getId(),
                savedMessages.getFirst().getId(),
                session.getDomainType(),
                "USER_MESSAGE_PERSISTED",
                "SUCCESS",
                java.util.Map.of("sequenceNumber", savedMessages.getFirst().getSequenceNumber())
        ));
        chatEventRecorder.recordAfterCommit(new ChatEventRecorder.ChatEventDraft(
                session.getId(),
                savedMessages.getLast().getId(),
                session.getDomainType(),
                "ASSISTANT_MESSAGE_COMPLETED",
                "SUCCESS",
                java.util.Map.of("sequenceNumber", savedMessages.getLast().getSequenceNumber())
        ));
    }
}
