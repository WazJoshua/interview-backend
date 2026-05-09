package com.josh.interviewj.chat.service;

import com.josh.interviewj.chat.config.ChatProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatTimelineServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private ChatTimelineService chatTimelineService;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        ChatProperties properties = new ChatProperties();
        properties.setTimelineWindowSize(100);
        chatTimelineService = new ChatTimelineService(chatMessageRepository, JsonMapper.builder().build(), properties);
        session = ChatSession.builder()
                .id(100L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(UUID.randomUUID())
                .status(ChatSessionStatus.ACTIVE)
                .messageCount(120)
                .nextMessageSequence(121)
                .build();
    }

    @Test
    void getTimeline_ReturnsRecentHundredMessagesOldestFirstAndMarksTruncated() {
        List<ChatMessage> descendingMessages = new ArrayList<>();
        for (int sequence = 120; sequence >= 21; sequence--) {
            descendingMessages.add(message(sequence, "message-" + sequence));
        }
        when(chatMessageRepository.findRecentByChatSessionId(100L, PageRequest.of(0, 100)))
                .thenReturn(descendingMessages);
        when(chatMessageRepository.countByChatSessionId(100L)).thenReturn(120L);

        ChatContextWindow timeline = chatTimelineService.getTimeline(session);

        verify(chatMessageRepository).findRecentByChatSessionId(100L, PageRequest.of(0, 100));
        assertTrue(timeline.truncated());
        assertEquals(100, timeline.returnedCount());
        assertEquals(120L, timeline.totalMessageCount());
        assertEquals(100, timeline.messages().size());
        assertEquals("message-21", timeline.messages().getFirst().content());
        assertEquals("message-120", timeline.messages().getLast().content());
    }

    @Test
    void getTimeline_WhenMessageCountWithinWindow_ReturnsFullTimeline() {
        when(chatMessageRepository.findRecentByChatSessionId(100L, PageRequest.of(0, 100)))
                .thenReturn(List.of(message(2, "assistant"), message(1, "user")));
        when(chatMessageRepository.countByChatSessionId(100L)).thenReturn(2L);

        ChatContextWindow timeline = chatTimelineService.getTimeline(session);

        assertFalse(timeline.truncated());
        assertEquals(2, timeline.returnedCount());
        assertEquals(2L, timeline.totalMessageCount());
        assertEquals("user", timeline.messages().getFirst().content());
        assertEquals("assistant", timeline.messages().getLast().content());
    }

    @Test
    void getTimeline_WhenInterviewMessagesExist_PreservesInterviewMessageTypesAndMetadata() {
        ChatMessage interviewQuestion = message(
                2,
                ChatRole.ASSISTANT,
                ChatMessageType.valueOf("INTERVIEW_QUESTION"),
                "Tell me about a time you debugged a production issue.",
                "{\"questionId\":\"q-1\",\"questionKind\":\"MAIN\"}"
        );
        ChatMessage interviewAnswer = message(
                1,
                ChatRole.USER,
                ChatMessageType.valueOf("INTERVIEW_ANSWER"),
                "I started by checking the error budget alerts.",
                "{\"answerId\":\"a-1\",\"sequenceNumber\":1}"
        );
        when(chatMessageRepository.findRecentByChatSessionId(100L, PageRequest.of(0, 100)))
                .thenReturn(List.of(interviewQuestion, interviewAnswer));
        when(chatMessageRepository.countByChatSessionId(100L)).thenReturn(2L);

        ChatContextWindow timeline = chatTimelineService.getTimeline(session);

        assertEquals(ChatMessageType.valueOf("INTERVIEW_ANSWER"), timeline.messages().getFirst().messageType());
        assertEquals(ChatMessageType.valueOf("INTERVIEW_QUESTION"), timeline.messages().getLast().messageType());
        assertEquals("a-1", timeline.messages().getFirst().metadata().get("answerId").asText());
        assertEquals("MAIN", timeline.messages().getLast().metadata().get("questionKind").asText());
    }

    private ChatMessage message(int sequenceNumber, String content) {
        return message(
                sequenceNumber,
                sequenceNumber % 2 == 0 ? ChatRole.ASSISTANT : ChatRole.USER,
                sequenceNumber % 2 == 0 ? ChatMessageType.ANSWER : ChatMessageType.TEXT,
                content,
                "{\"sequence\":" + sequenceNumber + "}"
        );
    }

    private ChatMessage message(
            int sequenceNumber,
            ChatRole role,
            ChatMessageType messageType,
            String content,
            String metadata
    ) {
        return ChatMessage.builder()
                .id((long) sequenceNumber)
                .externalId(UUID.randomUUID())
                .chatSessionId(100L)
                .role(role)
                .messageType(messageType)
                .content(content)
                .metadata(metadata)
                .sequenceNumber(sequenceNumber)
                .estimatedTokenCount(content.length())
                .createdAt(LocalDateTime.now().plusSeconds(sequenceNumber))
                .build();
    }
}
