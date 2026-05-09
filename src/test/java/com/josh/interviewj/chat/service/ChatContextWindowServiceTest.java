package com.josh.interviewj.chat.service;

import com.josh.interviewj.chat.dto.ChatContextWindow;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatContextWindowServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private ChatContextWindowService chatContextWindowService;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        chatContextWindowService = new ChatContextWindowService(chatMessageRepository, JsonMapper.builder().build());
        session = ChatSession.builder()
                .id(200L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(UUID.randomUUID())
                .status(ChatSessionStatus.ACTIVE)
                .build();
    }

    @Test
    void getRecentWindow_ReturnsRecentNMessagesOldestFirst() {
        when(chatMessageRepository.findRecentByChatSessionId(200L, PageRequest.of(0, 3)))
                .thenReturn(List.of(message(5, ChatRole.USER, ChatMessageType.TEXT, "m5", 2),
                        message(4, ChatRole.ASSISTANT, ChatMessageType.ANSWER, "m4", 2),
                        message(3, ChatRole.USER, ChatMessageType.TEXT, "m3", 2)));

        ChatContextWindow window = chatContextWindowService.getRecentWindow(session, 3, Set.of(), Set.of(), 100);

        assertEquals(List.of("m3", "m4", "m5"), window.messages().stream().map(message -> message.content()).toList());
    }

    @Test
    void getWindowBeforeMessage_FiltersByRoleAndMessageType() {
        ChatMessage anchor = message(6, ChatRole.ASSISTANT, ChatMessageType.ANSWER, "anchor", 6);
        when(chatMessageRepository.findByChatSessionIdAndExternalId(200L, anchor.getExternalId())).thenReturn(java.util.Optional.of(anchor));
        when(chatMessageRepository.findWindowUpToSequenceNumber(200L, 6, PageRequest.of(0, 6)))
                .thenReturn(List.of(
                        anchor,
                        message(5, ChatRole.USER, ChatMessageType.TEXT, "user-5", 4),
                        message(4, ChatRole.SYSTEM, ChatMessageType.TEXT, "system-4", 4),
                        message(3, ChatRole.ASSISTANT, ChatMessageType.ANSWER, "assistant-3", 4),
                        message(2, ChatRole.USER, ChatMessageType.TEXT, "user-2", 4),
                        message(1, ChatRole.ASSISTANT, ChatMessageType.SOURCE_SNAPSHOT, "snapshot-1", 4)
                ));

        ChatContextWindow window = chatContextWindowService.getWindowBeforeMessage(
                session,
                anchor.getExternalId(),
                6,
                Set.of(ChatRole.USER, ChatRole.ASSISTANT),
                Set.of(ChatMessageType.TEXT, ChatMessageType.ANSWER),
                100
        );

        assertEquals(List.of("user-2", "assistant-3", "user-5", "anchor"),
                window.messages().stream().map(message -> message.content()).toList());
    }

    @Test
    void getRecentWindow_WhenBudgetExceeded_TrimsOldestMessagesButKeepsOrder() {
        when(chatMessageRepository.findRecentByChatSessionId(200L, PageRequest.of(0, 4)))
                .thenReturn(List.of(
                        message(4, ChatRole.ASSISTANT, ChatMessageType.ANSWER, "4444", 4),
                        message(3, ChatRole.USER, ChatMessageType.TEXT, "3333", 4),
                        message(2, ChatRole.ASSISTANT, ChatMessageType.ANSWER, "2222", 4),
                        message(1, ChatRole.USER, ChatMessageType.TEXT, "1111", 4)
                ));

        ChatContextWindow window = chatContextWindowService.getRecentWindow(session, 4, Set.of(), Set.of(), 8);

        assertTrue(window.truncated());
        assertEquals(List.of("3333", "4444"), window.messages().stream().map(message -> message.content()).toList());
    }

    @Test
    void getRecentWindow_WhenMiddleMessageExceedsRemainingBudget_ReturnsContiguousSuffixOnly() {
        when(chatMessageRepository.findRecentByChatSessionId(200L, PageRequest.of(0, 4)))
                .thenReturn(List.of(
                        message(4, ChatRole.ASSISTANT, ChatMessageType.ANSWER, "4444", 4),
                        message(3, ChatRole.USER, ChatMessageType.TEXT, "33333", 5),
                        message(2, ChatRole.ASSISTANT, ChatMessageType.ANSWER, "2", 1),
                        message(1, ChatRole.USER, ChatMessageType.TEXT, "11", 2)
                ));

        ChatContextWindow window = chatContextWindowService.getRecentWindow(session, 4, Set.of(), Set.of(), 5);

        assertTrue(window.truncated());
        assertEquals(List.of("4444"), window.messages().stream().map(message -> message.content()).toList());
    }

    private ChatMessage message(
            int sequenceNumber,
            ChatRole role,
            ChatMessageType messageType,
            String content,
            int estimatedTokenCount
    ) {
        return ChatMessage.builder()
                .id((long) sequenceNumber)
                .externalId(UUID.randomUUID())
                .chatSessionId(200L)
                .role(role)
                .messageType(messageType)
                .content(content)
                .metadata("{\"sequence\":" + sequenceNumber + "}")
                .sequenceNumber(sequenceNumber)
                .estimatedTokenCount(estimatedTokenCount)
                .createdAt(LocalDateTime.now().plusSeconds(sequenceNumber))
                .build();
    }
}
