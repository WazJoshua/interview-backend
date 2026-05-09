package com.josh.interviewj.controller;

import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.chat.service.ChatSessionQueryService;
import com.josh.interviewj.ragqa.controller.KnowledgeBaseChatSessionController;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseChatMessageTimelineResponse;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseChatSessionItemResponse;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseChatSessionListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseChatSessionControllerTest {

    @Mock
    private ChatSessionQueryService chatSessionQueryService;

    @InjectMocks
    private KnowledgeBaseChatSessionController knowledgeBaseChatSessionController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(knowledgeBaseChatSessionController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listSessions_ReturnsPagedSessions() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(chatSessionQueryService.listKnowledgeBaseSessions("testuser", kbId, 0, 20, null))
                .thenReturn(new KnowledgeBaseChatSessionListResponse(
                        List.of(new KnowledgeBaseChatSessionItemResponse(
                                sessionId,
                                "",
                                "latest answer",
                                4,
                                ChatSessionStatus.ACTIVE,
                                LocalDateTime.parse("2026-03-20T09:00:00"),
                                LocalDateTime.parse("2026-03-20T09:02:10")
                        )),
                        0,
                        20,
                        1,
                        1,
                        true,
                        true,
                        false
                ));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/sessions", kbId)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].chatSessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.data.content[0].title").value(""))
                .andExpect(jsonPath("$.data.content[0].messageCount").value(4))
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void listSessions_InvalidPage_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/sessions", UUID.randomUUID())
                        .param("page", "-1")
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"));
    }

    @Test
    void listSessions_InvalidSize_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/sessions", UUID.randomUUID())
                        .param("size", "0")
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"));
    }

    @Test
    void getMessages_ReturnsTimelineShapeWithTruncationMetadata() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(chatSessionQueryService.getKnowledgeBaseTimeline("testuser", kbId, sessionId))
                .thenReturn(new KnowledgeBaseChatMessageTimelineResponse(
                        List.of(new KnowledgeBaseChatMessageTimelineResponse.MessageItem(
                                messageId,
                                "USER",
                                "TEXT",
                                "Redis?",
                                new java.util.LinkedHashMap<>(),
                                LocalDateTime.parse("2026-03-20T09:00:01")
                        )),
                        true,
                        100,
                        168
                ));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/sessions/{sessionId}/messages", kbId, sessionId)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.returnedCount").value(100))
                .andExpect(jsonPath("$.data.totalMessageCount").value(168));
    }

    @Test
    void getMessages_CrossKnowledgeBaseSession_ReturnsNotFound() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(chatSessionQueryService.getKnowledgeBaseTimeline("testuser", kbId, sessionId))
                .thenThrow(new BusinessException("KB_001", "Session not found"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/sessions/{sessionId}/messages", kbId, sessionId)
                        .principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("KB_001"));
    }
}
