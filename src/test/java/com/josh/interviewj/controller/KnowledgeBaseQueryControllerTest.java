package com.josh.interviewj.controller;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.ragqa.controller.KnowledgeBaseQueryController;
import com.josh.interviewj.ragqa.dto.request.KnowledgeBaseQueryAskRequest;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseQueryResponse;
import com.josh.interviewj.ragqa.service.KnowledgeBaseQueryService;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseQueryControllerTest {

    @Mock
    private KnowledgeBaseQueryService knowledgeBaseQueryService;

    @InjectMocks
    private KnowledgeBaseQueryController knowledgeBaseQueryController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(knowledgeBaseQueryController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void queryKnowledgeBase_Success_ReturnsOkWithAnswerAndSources() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID chatSessionId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        KnowledgeBaseQueryResponse response = KnowledgeBaseQueryResponse.builder()
                .answer("Redis uses persistence")
                .chatSessionId(chatSessionId)
                .userMessageId(userMessageId)
                .assistantMessageId(assistantMessageId)
                .confidence(0.91D)
                .retrievedChunkCount(1)
                .processingTime(15L)
                .sources(java.util.List.of(KnowledgeBaseQueryResponse.Source.builder()
                        .documentId(documentId)
                        .documentName("redis.pdf")
                        .chunkIndex(0)
                        .content("Redis persistence chunk")
                        .similarity(0.91D)
                        .build()))
                .build();

        when(knowledgeBaseQueryService.askQuestion(eq("testuser"), eq(kbId), any(KnowledgeBaseQueryAskRequest.class)))
                .thenReturn(response);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(post("/api/v1/knowledge-bases/{id}/query", kbId)
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {"question":"Redis?","topK":5,"includeSources":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("Redis uses persistence"))
                .andExpect(jsonPath("$.data.chatSessionId").value(chatSessionId.toString()))
                .andExpect(jsonPath("$.data.userMessageId").value(userMessageId.toString()))
                .andExpect(jsonPath("$.data.assistantMessageId").value(assistantMessageId.toString()))
                .andExpect(jsonPath("$.data.sources[0].documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.data.sources[0].chunkIndex").value(0));
    }

    @Test
    void queryKnowledgeBase_InvalidChatSessionId_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(post("/api/v1/knowledge-bases/{id}/query", UUID.randomUUID())
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {"question":"Redis?","topK":5,"includeSources":true,"chatSessionId":"not-a-uuid"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
    }

    @Test
    void queryKnowledgeBase_BlankQuestion_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(post("/api/v1/knowledge-bases/{id}/query", UUID.randomUUID())
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {"question":"   ","topK":5,"includeSources":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("question"));
    }

    @Test
    void queryKnowledgeBase_TopKTooLarge_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(post("/api/v1/knowledge-bases/{id}/query", UUID.randomUUID())
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {"question":"Redis?","topK":21,"includeSources":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("topK"));
    }

    @Test
    void queryKnowledgeBase_NoPermission_ReturnsForbidden() throws Exception {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseQueryService.askQuestion(eq("testuser"), eq(kbId), any(KnowledgeBaseQueryAskRequest.class)))
                .thenThrow(new BusinessException("AUTH_006", "Forbidden"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(post("/api/v1/knowledge-bases/{id}/query", kbId)
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {"question":"Redis?","topK":5,"includeSources":true}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("AUTH_006"));
    }

    @Test
    void queryKnowledgeBase_NoAvailableChunks_ReturnsUnprocessableEntity() throws Exception {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseQueryService.askQuestion(eq("testuser"), eq(kbId), any(KnowledgeBaseQueryAskRequest.class)))
                .thenThrow(new BusinessException("KB_004", "No relevant content found"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(post("/api/v1/knowledge-bases/{id}/query", kbId)
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {"question":"Redis?","topK":5,"includeSources":true}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.type").value("KB_004"));
    }
}
