package com.josh.interviewj.chat.service;

import com.josh.interviewj.chat.dto.ChatMessageDraft;
import com.josh.interviewj.chat.dto.ChatTurnWriteResult;
import com.josh.interviewj.chat.config.ChatProperties;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatWriteServiceTest {

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatEventRecorder chatEventRecorder;

    private ChatWriteService chatWriteService;

    @BeforeEach
    void setUp() {
        ChatProperties chatProperties = new ChatProperties();
        chatWriteService = new ChatWriteService(
                chatSessionRepository,
                chatMessageRepository,
                new ChatSessionAccessPolicy(),
                chatEventRecorder,
                chatProperties
        );
    }

    @Test
    void appendTurn_NewSession_AssignsSequenceOneAndTwoAndRefreshesStats() {
        ChatSession session = newSession(null, 1, 0);
        ChatSession lockedSession = newSession(10L, 1, 0);
        lockedSession.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        lockedSession.setUpdatedAt(LocalDateTime.now().minusMinutes(1));

        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> {
            ChatSession saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(10L);
            }
            return saved;
        });
        when(chatSessionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(lockedSession));
        when(chatMessageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatTurnWriteResult result = chatWriteService.appendTurn(
                session,
                draft(ChatRole.USER, ChatMessageType.TEXT, "Redis 持久化机制有哪些？", null),
                draft(ChatRole.ASSISTANT, ChatMessageType.ANSWER, "Redis 提供 RDB 和 AOF。", "{\"confidence\":0.9}")
        );

        ArgumentCaptor<List<ChatMessage>> messageCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageRepository).saveAll(messageCaptor.capture());
        verify(chatSessionRepository).save(lockedSession);

        List<ChatMessage> persistedMessages = messageCaptor.getValue();
        assertEquals(2, persistedMessages.size());
        assertEquals(1, persistedMessages.getFirst().getSequenceNumber());
        assertEquals(2, persistedMessages.getLast().getSequenceNumber());
        assertEquals(2, lockedSession.getMessageCount());
        assertEquals(3, lockedSession.getNextMessageSequence());
        assertEquals("Redis 提供 RDB 和 AOF。", lockedSession.getLastMessagePreview());
        assertEquals(lockedSession.getExternalId(), result.chatSessionId());
        assertEquals(persistedMessages.getFirst().getExternalId(), result.userMessageId());
        assertEquals(persistedMessages.getLast().getExternalId(), result.assistantMessageId());
    }

    @Test
    void appendTurn_ExistingSession_AssignsNextSequenceWindow() {
        ChatSession session = newSession(12L, 3, 2);

        when(chatSessionRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(session));
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatMessageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        chatWriteService.appendTurn(
                session,
                draft(ChatRole.USER, ChatMessageType.TEXT, "继续说 AOF 的优缺点", null),
                draft(ChatRole.ASSISTANT, ChatMessageType.ANSWER, "AOF 更耐久，但恢复通常更慢。", "{\"confidence\":0.8}")
        );

        ArgumentCaptor<List<ChatMessage>> messageCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageRepository).saveAll(messageCaptor.capture());
        List<ChatMessage> persistedMessages = messageCaptor.getValue();
        assertEquals(3, persistedMessages.getFirst().getSequenceNumber());
        assertEquals(4, persistedMessages.getLast().getSequenceNumber());
        assertEquals(4, session.getMessageCount());
        assertEquals(5, session.getNextMessageSequence());
    }

    @Test
    void appendTurn_ArchivedSession_ThrowsConflict() {
        ChatSession archived = newSession(15L, 1, 0);
        archived.setStatus(ChatSessionStatus.ARCHIVED);
        when(chatSessionRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(archived));

        assertThrows(BusinessException.class, () -> chatWriteService.appendTurn(
                archived,
                draft(ChatRole.USER, ChatMessageType.TEXT, "还能继续问吗？", null),
                draft(ChatRole.ASSISTANT, ChatMessageType.ANSWER, "不能继续写。", null)
        ));
    }

    @Test
    void appendTurn_UsesConfiguredPreviewLength() {
        ChatProperties chatProperties = new ChatProperties();
        chatProperties.setMaxPreviewLength(12);
        chatWriteService = new ChatWriteService(
                chatSessionRepository,
                chatMessageRepository,
                new ChatSessionAccessPolicy(),
                chatEventRecorder,
                chatProperties
        );
        ChatSession session = newSession(12L, 1, 0);

        when(chatSessionRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(session));
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatMessageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        chatWriteService.appendTurn(
                session,
                draft(ChatRole.USER, ChatMessageType.TEXT, "问", null),
                draft(ChatRole.ASSISTANT, ChatMessageType.ANSWER, "12345678901234567890", null)
        );

        assertEquals("123456789012", session.getLastMessagePreview());
    }

    private ChatMessageDraft draft(ChatRole role, ChatMessageType messageType, String content, String metadata) {
        return new ChatMessageDraft(role, messageType, content, metadata, null, null);
    }

    private ChatSession newSession(Long id, int nextSequence, int messageCount) {
        return ChatSession.builder()
                .id(id)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(UUID.randomUUID())
                .status(ChatSessionStatus.ACTIVE)
                .title("")
                .nextMessageSequence(nextSequence)
                .messageCount(messageCount)
                .build();
    }
}
