package com.josh.interviewj.chat.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSecurityBoundaryTest {

    @Mock
    private KnowledgeBaseAccessService knowledgeBaseAccessService;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatTimelineService chatTimelineService;

    private ChatSessionQueryService chatSessionQueryService;
    private User owner;
    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        chatSessionQueryService = new ChatSessionQueryService(
                knowledgeBaseAccessService,
                chatSessionRepository,
                chatTimelineService,
                new ChatSessionAccessPolicy(),
                JsonMapper.builder().build()
        );

        owner = User.builder()
                .id(1L)
                .username("owner")
                .email("owner@example.com")
                .password("hashed")
                .build();

        knowledgeBase = KnowledgeBase.builder()
                .id(2L)
                .externalId(UUID.randomUUID())
                .userId(owner.getId())
                .name("KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(knowledgeBaseAccessService.requireUser("owner")).thenReturn(owner);
        when(knowledgeBaseAccessService.requireReadableKnowledgeBase("owner", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
    }

    @Test
    void getKnowledgeBaseTimeline_CrossKnowledgeBaseSession_ReturnsNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(chatSessionRepository.findByExternalIdAndUserIdAndDomainTypeAndDomainRefTypeAndDomainRefExternalId(
                sessionId,
                owner.getId(),
                ChatDomainType.RAG_QA,
                ChatDomainRefType.KNOWLEDGE_BASE,
                knowledgeBase.getExternalId()
        )).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> chatSessionQueryService.getKnowledgeBaseTimeline(
                "owner",
                knowledgeBase.getExternalId(),
                sessionId
        ));
    }

    @Test
    void listKnowledgeBaseSessions_ScopesQueryByUserAndKnowledgeBase() {
        when(chatSessionRepository.findByUserIdAndDomainTypeAndDomainRefTypeAndDomainRefExternalIdOrderByUpdatedAtDesc(
                owner.getId(),
                ChatDomainType.RAG_QA,
                ChatDomainRefType.KNOWLEDGE_BASE,
                knowledgeBase.getExternalId(),
                PageRequest.of(0, 20)
        )).thenReturn(new PageImpl<>(List.of()));

        var response = chatSessionQueryService.listKnowledgeBaseSessions("owner", knowledgeBase.getExternalId(), 0, 20, null);

        verify(chatSessionRepository).findByUserIdAndDomainTypeAndDomainRefTypeAndDomainRefExternalIdOrderByUpdatedAtDesc(
                owner.getId(),
                ChatDomainType.RAG_QA,
                ChatDomainRefType.KNOWLEDGE_BASE,
                knowledgeBase.getExternalId(),
                PageRequest.of(0, 20)
        );
        assertEquals(0, response.content().size());
    }
}
