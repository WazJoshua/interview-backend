package com.josh.interviewj.kb;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.chat.service.ChatSessionQueryService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseChatMessageTimelineResponse;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseChatSessionListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class KnowledgeBaseChatSessionIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatSessionQueryService chatSessionQueryService;

    private User owner;
    private User outsider;
    private KnowledgeBase knowledgeBase;
    private ChatSession activeSession;
    private ChatSession archivedSession;

    @BeforeEach
    void setUp() {
        chatMessageRepository.deleteAllInBatch();
        chatSessionRepository.deleteAllInBatch();
        knowledgeBaseRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        owner = userRepository.save(User.builder()
                .username("chat-owner")
                .email("chat-owner@example.com")
                .password("hashed")
                .build());

        outsider = userRepository.save(User.builder()
                .username("chat-outsider")
                .email("chat-outsider@example.com")
                .password("hashed")
                .build());

        knowledgeBase = knowledgeBaseRepository.save(KnowledgeBase.builder()
                .userId(owner.getId())
                .name("Chat KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .build());

        activeSession = chatSessionRepository.save(ChatSession.builder()
                .userId(owner.getId())
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(knowledgeBase.getExternalId())
                .status(ChatSessionStatus.ACTIVE)
                .messageCount(2)
                .lastMessagePreview("active-preview")
                .lastMessageAt(LocalDateTime.now().minusMinutes(1))
                .build());

        archivedSession = chatSessionRepository.save(ChatSession.builder()
                .userId(owner.getId())
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(knowledgeBase.getExternalId())
                .status(ChatSessionStatus.ARCHIVED)
                .messageCount(2)
                .lastMessagePreview("archived-preview")
                .lastMessageAt(LocalDateTime.now())
                .build());

        chatMessageRepository.save(ChatMessage.builder()
                .chatSessionId(archivedSession.getId())
                .role(ChatRole.USER)
                .messageType(ChatMessageType.TEXT)
                .content("Q1")
                .sequenceNumber(1)
                .createdAt(LocalDateTime.now().minusSeconds(2))
                .build());
        chatMessageRepository.save(ChatMessage.builder()
                .chatSessionId(archivedSession.getId())
                .role(ChatRole.ASSISTANT)
                .messageType(ChatMessageType.ANSWER)
                .content("A1")
                .sequenceNumber(2)
                .createdAt(LocalDateTime.now().minusSeconds(1))
                .build());
    }

    @Test
    void listKnowledgeBaseSessions_ReturnsArchivedSessionInUpdatedOrder() {
        KnowledgeBaseChatSessionListResponse response = chatSessionQueryService.listKnowledgeBaseSessions(
                owner.getUsername(),
                knowledgeBase.getExternalId(),
                0,
                20,
                null
        );

        assertEquals(2, response.content().size());
        assertEquals(archivedSession.getExternalId(), response.content().getFirst().chatSessionId());
        assertEquals(ChatSessionStatus.ARCHIVED, response.content().getFirst().status());
    }

    @Test
    void getKnowledgeBaseTimeline_ArchivedSessionIsReadable() {
        KnowledgeBaseChatMessageTimelineResponse response = chatSessionQueryService.getKnowledgeBaseTimeline(
                owner.getUsername(),
                knowledgeBase.getExternalId(),
                archivedSession.getExternalId()
        );

        assertEquals(2, response.messages().size());
        assertEquals("Q1", response.messages().getFirst().content());
        assertEquals("A1", response.messages().getLast().content());
    }

    @Test
    void getKnowledgeBaseTimeline_OutsiderCannotReadOtherUsersSessions() {
        assertThrows(BusinessException.class, () -> chatSessionQueryService.getKnowledgeBaseTimeline(
                outsider.getUsername(),
                knowledgeBase.getExternalId(),
                archivedSession.getExternalId()
        ));
    }
}
