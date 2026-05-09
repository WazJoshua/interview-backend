package com.josh.interviewj.controller;

import com.josh.interviewj.knowledgebase.dto.request.KnowledgeBaseCreateRequest;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentArtifactResponse;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentDetailResponse;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentResponse;
import com.josh.interviewj.knowledgebase.dto.response.KnowledgeBaseReindexResponse;
import com.josh.interviewj.knowledgebase.dto.response.KnowledgeBaseResponse;
import com.josh.interviewj.knowledgebase.dto.response.KnowledgeBaseStatsResponse;
import com.josh.interviewj.knowledgebase.model.ChunkStrategy;
import com.josh.interviewj.knowledgebase.model.KbDocumentArtifactType;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.knowledgebase.controller.KnowledgeBaseController;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseLifecycleService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseReindexService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseControllerTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private KnowledgeBaseLifecycleService knowledgeBaseLifecycleService;

    @Mock
    private KnowledgeBaseStatsService knowledgeBaseStatsService;

    @Mock
    private KnowledgeBaseReindexService knowledgeBaseReindexService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private KnowledgeBaseController knowledgeBaseController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(knowledgeBaseController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createKnowledgeBase_ValidRequest_ReturnsCreated() throws Exception {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseResponse response = KnowledgeBaseResponse.builder()
                .id(kbId)
                .name("Java KB")
                .description("Spring docs")
                .embeddingModel("text-embedding-v4")
                .vectorDimension(2048)
                .documentCount(0)
                .totalChunks(0)
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        when(knowledgeBaseService.createKnowledgeBase(eq("testuser"), any(KnowledgeBaseCreateRequest.class)))
                .thenReturn(response);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {"name":"Java KB","description":"Spring docs","embeddingModel":"text-embedding-v4"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/knowledge-bases/" + kbId))
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.name").value("Java KB"));
    }

    @Test
    void createKnowledgeBase_BlankName_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {"name":"   ","description":"Spring docs","embeddingModel":"text-embedding-v4"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"));
    }

    @Test
    void createKnowledgeBase_DescriptionTooLong_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        String tooLongDescription = "a".repeat(2001);

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {"name":"Java KB","description":"%s","embeddingModel":"text-embedding-v4"}
                                """.formatted(tooLongDescription)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("description"));
    }

    @Test
    void uploadDocument_ValidRequest_ReturnsAccepted() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        KbDocumentResponse response = KbDocumentResponse.builder()
                .id(documentId)
                .kbId(kbId)
                .fileName("redis.pdf")
                .fileType("application/pdf")
                .fileSize(1234L)
                .chunkStrategy(ChunkStrategy.FIXED_SIZE)
                .status(KbDocumentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        when(knowledgeBaseService.uploadDocument(eq("testuser"), eq(kbId), any(), eq(ChunkStrategy.FIXED_SIZE)))
                .thenReturn(response);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        MockMultipartFile file = new MockMultipartFile("file", "redis.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/v1/knowledge-bases/{id}/documents", kbId)
                        .file(file)
                        .param("chunkStrategy", "FIXED_SIZE")
                        .principal(authentication))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(202))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void getKnowledgeBases_Success_ReturnsPagedPayload() throws Exception {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseResponse response = KnowledgeBaseResponse.builder()
                .id(kbId)
                .name("Java KB")
                .description("Spring docs")
                .embeddingModel("text-embedding-v4")
                .vectorDimension(2048)
                .documentCount(2)
                .totalChunks(10)
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
        Page<KnowledgeBaseResponse> page = new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1);

        when(knowledgeBaseService.getKnowledgeBases(eq("testuser"), any())).thenReturn(page);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content[0].id").value(kbId.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void getKnowledgeBase_Success_ReturnsDetail() throws Exception {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseResponse response = KnowledgeBaseResponse.builder()
                .id(kbId)
                .name("Java KB")
                .description("Spring docs")
                .embeddingModel("text-embedding-v4")
                .vectorDimension(2048)
                .documentCount(1)
                .totalChunks(4)
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(knowledgeBaseService.getKnowledgeBase("testuser", kbId)).thenReturn(response);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}", kbId)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(kbId.toString()))
                .andExpect(jsonPath("$.data.name").value("Java KB"));
    }

    @Test
    void getKnowledgeBase_NoPermission_ReturnsForbidden() throws Exception {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.getKnowledgeBase("testuser", kbId))
                .thenThrow(new BusinessException("AUTH_006", "Forbidden"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}", kbId)
                        .principal(authentication))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("AUTH_006"));
    }

    @Test
    void getKnowledgeBase_NotFound_ReturnsNotFound() throws Exception {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.getKnowledgeBase("testuser", kbId))
                .thenThrow(new BusinessException("KB_001", "知识库不存在"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}", kbId)
                        .principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("KB_001"));
    }

    @Test
    void updateKnowledgeBase_EmptyBody_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(put("/api/v1/knowledge-bases/{id}", UUID.randomUUID())
                        .principal(authentication)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("anyFieldProvided"));
    }

    @Test
    void updateKnowledgeBase_NameOnly_ReturnsUpdatedPayload() throws Exception {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseResponse response = KnowledgeBaseResponse.builder()
                .id(kbId)
                .name("Renamed KB")
                .description("Spring docs")
                .embeddingModel("text-embedding-v4")
                .vectorDimension(2048)
                .documentCount(1)
                .totalChunks(4)
                .status(KnowledgeBaseStatus.ACTIVE)
                .indexingStatus(KnowledgeBaseIndexingStatus.REINDEXING)
                .build();
        when(knowledgeBaseLifecycleService.updateKnowledgeBase(eq("testuser"), eq(kbId), any()))
                .thenReturn(response);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(put("/api/v1/knowledge-bases/{id}", kbId)
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {"name":"Renamed KB"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(kbId.toString()))
                .andExpect(jsonPath("$.data.name").value("Renamed KB"))
                .andExpect(jsonPath("$.data.indexingStatus").value("REINDEXING"));
    }

    @Test
    void updateKnowledgeBase_DescriptionOnly_ReturnsUpdatedPayload() throws Exception {
        UUID kbId = UUID.randomUUID();
        KnowledgeBaseResponse response = KnowledgeBaseResponse.builder()
                .id(kbId)
                .name("Java KB")
                .description("Updated description")
                .embeddingModel("text-embedding-v4")
                .vectorDimension(2048)
                .documentCount(1)
                .totalChunks(4)
                .status(KnowledgeBaseStatus.ACTIVE)
                .build();
        when(knowledgeBaseLifecycleService.updateKnowledgeBase(eq("testuser"), eq(kbId), any()))
                .thenReturn(response);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(put("/api/v1/knowledge-bases/{id}", kbId)
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {"description":"Updated description"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(kbId.toString()))
                .andExpect(jsonPath("$.data.description").value("Updated description"));
    }

    @Test
    void getDocuments_Success_ReturnsPagedPayload() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        KbDocumentResponse response = KbDocumentResponse.builder()
                .id(documentId)
                .kbId(kbId)
                .fileName("redis.pdf")
                .fileType("application/pdf")
                .fileSize(1234L)
                .chunkCount(6)
                .chunkStrategy(ChunkStrategy.FIXED_SIZE)
                .status(KbDocumentStatus.COMPLETED)
                .createdAt(LocalDateTime.now())
                .build();
        Page<KbDocumentResponse> page = new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1);

        when(knowledgeBaseService.getDocuments(eq("testuser"), eq(kbId), any())).thenReturn(page);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/documents", kbId)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content[0].id").value(documentId.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void getDocuments_NoPermission_ReturnsForbidden() throws Exception {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.getDocuments(eq("testuser"), eq(kbId), any()))
                .thenThrow(new BusinessException("AUTH_006", "Forbidden"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/documents", kbId)
                        .principal(authentication))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("AUTH_006"));
    }

    @Test
    void getKnowledgeBases_InvalidPage_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases")
                        .principal(authentication)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("page"));
    }

    @Test
    void getDocuments_InvalidSize_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/documents", UUID.randomUUID())
                        .principal(authentication)
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
    }

    @Test
    void uploadDocument_UnsupportedChunkStrategy_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        MockMultipartFile file = new MockMultipartFile("file", "redis.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/v1/knowledge-bases/{id}/documents", UUID.randomUUID())
                        .file(file)
                        .param("chunkStrategy", "PARAGRAPH")
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
    }

    @Test
    void getDocumentDetail_ReturnsArtifactSummaryWhenPresent() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        KbDocumentDetailResponse response = KbDocumentDetailResponse.builder()
                .id(docId)
                .kbId(kbId)
                .fileName("guide.pdf")
                .fileType("application/pdf")
                .status(KbDocumentStatus.FAILED)
                .artifactSummary(KbDocumentArtifactResponse.builder()
                        .artifactType(KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT)
                        .exists(true)
                        .metadata(Map.of("reviewOnlyBlockCount", 2))
                        .updatedAt(LocalDateTime.now())
                        .build())
                .build();

        when(knowledgeBaseService.getDocumentDetail("testuser", kbId, docId)).thenReturn(response);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/documents/{docExternalId}", kbId, docId)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(docId.toString()))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.artifactSummary.artifactType").value("NORMALIZED_REVIEW_TEXT"))
                .andExpect(jsonPath("$.data.artifactSummary.exists").value(true));
    }

    @Test
    void getDocumentArtifact_ReturnsNormalizedReviewText() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        KbDocumentArtifactResponse response = KbDocumentArtifactResponse.builder()
                .artifactType(KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT)
                .exists(true)
                .content("[BLOCK]")
                .metadata(Map.of("reviewOnlyBlockCount", 2))
                .updatedAt(LocalDateTime.now())
                .build();

        when(knowledgeBaseService.getDocumentArtifact("testuser", kbId, docId, KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT))
                .thenReturn(response);

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/documents/{docExternalId}/artifacts/{artifactType}", kbId, docId, "NORMALIZED_REVIEW_TEXT")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifactType").value("NORMALIZED_REVIEW_TEXT"))
                .andExpect(jsonPath("$.data.content").value("[BLOCK]"));
    }

    @Test
    void deleteKnowledgeBase_ReturnsNoContent() throws Exception {
        UUID kbId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(delete("/api/v1/knowledge-bases/{id}", kbId)
                        .principal(authentication))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteDocument_ReturnsNoContent() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(delete("/api/v1/knowledge-bases/{id}/documents/{docExternalId}", kbId, docId)
                        .principal(authentication))
                .andExpect(status().isNoContent());
    }

    @Test
    void getKnowledgeBaseStats_ReturnsOk() throws Exception {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseStatsService.getStats("testuser", kbId))
                .thenReturn(KnowledgeBaseStatsResponse.builder()
                        .totalDocuments(2)
                        .totalChunks(10)
                        .totalSize(2048L)
                        .pendingDocuments(1)
                        .processingDocuments(0)
                        .failedDocuments(0)
                        .totalQueries(3L)
                        .averageConfidence(0.82D)
                        .indexingStatus(KnowledgeBaseIndexingStatus.REINDEXING)
                        .build());

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/stats", kbId)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalDocuments").value(2))
                .andExpect(jsonPath("$.data.averageConfidence").value(0.82))
                .andExpect(jsonPath("$.data.indexingStatus").value("REINDEXING"));
    }

    @Test
    void reindexKnowledgeBase_ReturnsAccepted() throws Exception {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseReindexService.reindex("testuser", kbId))
                .thenReturn(KnowledgeBaseReindexResponse.builder()
                        .kbId(kbId)
                        .totalDocuments(2)
                        .status("ACCEPTED")
                        .indexingStatus(KnowledgeBaseIndexingStatus.REINDEXING)
                        .build());

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(post("/api/v1/knowledge-bases/{id}/reindex", kbId)
                        .principal(authentication))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(202))
                .andExpect(jsonPath("$.data.kbId").value(kbId.toString()))
                .andExpect(jsonPath("$.data.indexingStatus").value("REINDEXING"));
    }

    @Test
    void getDocumentArtifact_DocumentOutsideKnowledgeBase_ReturnsNotFound() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(knowledgeBaseService.getDocumentArtifact("testuser", kbId, docId, KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT))
                .thenThrow(new BusinessException("KB_002", "文档不存在"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/documents/{docExternalId}/artifacts/{artifactType}", kbId, docId, "NORMALIZED_REVIEW_TEXT")
                        .principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("KB_002"));
    }

    @Test
    void getKnowledgeBases_InvalidStatus_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases")
                        .principal(authentication)
                        .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
    }

    @Test
    void getDocuments_InvalidStatus_ReturnsValidationError() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/documents", UUID.randomUUID())
                        .principal(authentication)
                        .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
    }

    @Test
    void getDocumentArtifact_UnsupportedArtifactType_ReturnsValidationError() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(get("/api/v1/knowledge-bases/{id}/documents/{docExternalId}/artifacts/{artifactType}", kbId, docId, "UNKNOWN_TYPE")
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("artifactType"));
    }
}
