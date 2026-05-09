package com.josh.interviewj.service;

import com.josh.interviewj.knowledgebase.dto.request.KnowledgeBaseCreateRequest;
import com.josh.interviewj.knowledgebase.dto.request.KnowledgeBaseQueryRequest;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentArtifactResponse;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentDetailResponse;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentResponse;
import com.josh.interviewj.knowledgebase.dto.response.KnowledgeBaseResponse;
import com.josh.interviewj.knowledgebase.model.KbDocumentArtifact;
import com.josh.interviewj.knowledgebase.model.KbDocumentArtifactType;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.knowledgebase.model.ChunkStrategy;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.service.KbDocumentArtifactService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseAccessService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseEmbeddingConfigService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseStorageService;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private KbDocumentRepository kbDocumentRepository;

    @Mock
    private KbDocumentOutboxRepository kbDocumentOutboxRepository;

    @Mock
    private KnowledgeBaseAccessService knowledgeBaseAccessService;

    @Mock
    private KnowledgeBaseStorageService knowledgeBaseStorageService;

    @Mock
    private KbDocumentArtifactService kbDocumentArtifactService;

    @Mock
    private KnowledgeBaseEmbeddingConfigService knowledgeBaseEmbeddingConfigService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private KnowledgeBaseService knowledgeBaseService;

    private User testUser;
    private KnowledgeBase knowledgeBase;
    private KbDocument kbDocument;

    @BeforeEach
    void setUp() {
        lenient().when(knowledgeBaseEmbeddingConfigService.getCurrentDocumentEmbedding())
                .thenReturn(new KnowledgeBaseEmbeddingConfigService.KnowledgeBaseEmbeddingConfig("text-embedding-v4", 2048));

        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");

        knowledgeBase = KnowledgeBase.builder()
                .id(10L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .name("Java KB")
                .description("Spring docs")
                .embeddingModel("text-embedding-v4")
                .vectorDimension(2048)
                .documentCount(0)
                .totalChunks(0)
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        kbDocument = KbDocument.builder()
                .id(100L)
                .externalId(UUID.randomUUID())
                .kbId(knowledgeBase.getId())
                .fileName("guide.pdf")
                .fileType("application/pdf")
                .fileSize(1024L)
                .chunkCount(2)
                .expectedChunkCount(2)
                .embeddedChunkCount(2)
                .chunkStrategy(ChunkStrategy.FIXED_SIZE)
                .status(KbDocumentStatus.COMPLETED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void createKnowledgeBase_Success() {
        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("Java KB");
        request.setDescription("Spring docs");
        request.setEmbeddingModel("text-embedding-v4");

        when(knowledgeBaseAccessService.requireUser("testuser")).thenReturn(testUser);
        when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenAnswer(invocation -> {
            KnowledgeBase saved = invocation.getArgument(0);
            saved.setId(10L);
            saved.setExternalId(knowledgeBase.getExternalId());
            saved.setCreatedAt(LocalDateTime.now());
            saved.setUpdatedAt(LocalDateTime.now());
            return saved;
        });

        KnowledgeBaseResponse response = knowledgeBaseService.createKnowledgeBase("testuser", request);

        assertNotNull(response.getId());
        assertEquals("Java KB", response.getName());
        assertEquals("text-embedding-v4", response.getEmbeddingModel());
        assertEquals(KnowledgeBaseStatus.ACTIVE, response.getStatus());
    }

    @Test
    void createKnowledgeBase_IgnoresRequestedEmbeddingModel_UsesConfiguredDocumentEmbedding() {
        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("Java KB");
        request.setDescription("Spring docs");
        request.setEmbeddingModel("frontend-sent-but-ignored");

        when(knowledgeBaseAccessService.requireUser("testuser")).thenReturn(testUser);
        when(knowledgeBaseEmbeddingConfigService.getCurrentDocumentEmbedding())
                .thenReturn(new KnowledgeBaseEmbeddingConfigService.KnowledgeBaseEmbeddingConfig("configured-embedding-v2", 3072));
        when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenAnswer(invocation -> {
            KnowledgeBase saved = invocation.getArgument(0);
            saved.setId(10L);
            saved.setExternalId(knowledgeBase.getExternalId());
            saved.setCreatedAt(LocalDateTime.now());
            saved.setUpdatedAt(LocalDateTime.now());
            return saved;
        });

        KnowledgeBaseResponse response = knowledgeBaseService.createKnowledgeBase("testuser", request);

        assertEquals("configured-embedding-v2", response.getEmbeddingModel());
        assertEquals(3072, response.getVectorDimension());

        ArgumentCaptor<KnowledgeBase> knowledgeBaseCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseRepository).save(knowledgeBaseCaptor.capture());
        assertEquals("configured-embedding-v2", knowledgeBaseCaptor.getValue().getEmbeddingModel());
        assertEquals(3072, knowledgeBaseCaptor.getValue().getVectorDimension());
    }

    @Test
    void getKnowledgeBases_DefaultListExcludesDeleted() {
        KnowledgeBase archived = KnowledgeBase.builder()
                .id(11L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .name("Archived KB")
                .status(KnowledgeBaseStatus.ARCHIVED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(knowledgeBaseAccessService.requireUser("testuser")).thenReturn(testUser);
        when(knowledgeBaseRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                eq(testUser.getId()),
                eq(List.of(KnowledgeBaseStatus.ACTIVE, KnowledgeBaseStatus.ARCHIVED)),
                any()
        )).thenReturn(new PageImpl<>(List.of(knowledgeBase, archived), PageRequest.of(0, 20), 2));

        KnowledgeBaseQueryRequest request = new KnowledgeBaseQueryRequest();

        var response = knowledgeBaseService.getKnowledgeBases("testuser", request);

        assertEquals(2, response.getTotalElements());
        verify(knowledgeBaseRepository).findByUserIdAndStatusInOrderByCreatedAtDesc(
                eq(testUser.getId()),
                eq(List.of(KnowledgeBaseStatus.ACTIVE, KnowledgeBaseStatus.ARCHIVED)),
                any()
        );
    }

    @Test
    void uploadDocument_Success_CreatesPendingDocumentAndOutbox() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "redis.pdf",
                "application/pdf",
                "hello kb".getBytes()
        );

        when(knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(Optional.of(knowledgeBase));
        when(knowledgeBaseStorageService.store(file)).thenReturn("kb/redis.pdf");
        when(kbDocumentRepository.save(any(KbDocument.class))).thenAnswer(invocation -> {
            KbDocument saved = invocation.getArgument(0);
            saved.setId(100L);
            saved.setExternalId(UUID.randomUUID());
            saved.setCreatedAt(LocalDateTime.now());
            return saved;
        });
        when(kbDocumentOutboxRepository.save(any(KbDocumentOutbox.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KbDocumentResponse response = knowledgeBaseService.uploadDocument(
                "testuser",
                knowledgeBase.getExternalId(),
                file,
                ChunkStrategy.FIXED_SIZE
        );

        assertNotNull(response.getId());
        assertEquals(KbDocumentStatus.PENDING, response.getStatus());
        assertEquals(ChunkStrategy.FIXED_SIZE, response.getChunkStrategy());

        ArgumentCaptor<KbDocument> documentCaptor = ArgumentCaptor.forClass(KbDocument.class);
        verify(kbDocumentRepository).save(documentCaptor.capture());
        assertEquals(KbDocumentStatus.PENDING, documentCaptor.getValue().getStatus());

        ArgumentCaptor<KbDocumentOutbox> outboxCaptor = ArgumentCaptor.forClass(KbDocumentOutbox.class);
        verify(kbDocumentOutboxRepository).save(outboxCaptor.capture());
        assertEquals(OutboxStatus.NEW, outboxCaptor.getValue().getStatus());
        assertEquals(knowledgeBase.getId(), outboxCaptor.getValue().getKbId());
    }

    @Test
    void uploadDocument_KnowledgeBaseOwnedByAnotherUser_ThrowsForbidden() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "redis.pdf",
                "application/pdf",
                "hello kb".getBytes()
        );

        when(knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenThrow(new BusinessException("AUTH_006", "Forbidden"));

        BusinessException exception = assertThrows(BusinessException.class, () -> knowledgeBaseService.uploadDocument(
                "testuser",
                knowledgeBase.getExternalId(),
                file,
                ChunkStrategy.FIXED_SIZE
        ));

        assertEquals("AUTH_006", exception.getErrorCode());
    }

    @Test
    void uploadDocument_UnsupportedFileFormat_ThrowsKnowledgeBaseFriendlyMessage() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "script.exe",
                "application/octet-stream",
                "bad".getBytes()
        );

        when(knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);

        BusinessException exception = assertThrows(BusinessException.class, () -> knowledgeBaseService.uploadDocument(
                "testuser",
                knowledgeBase.getExternalId(),
                file,
                ChunkStrategy.FIXED_SIZE
        ));

        assertEquals("不支持的文档格式", exception.getMessage());
    }

    @Test
    void uploadDocument_NonFixedSizeChunkStrategy_ThrowsValidationError() {
        MockMultipartFile file = new MockMultipartFile("file", "redis.pdf", "application/pdf", "hello".getBytes());

        when(knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(Optional.of(knowledgeBase));

        BusinessException exception = assertThrows(BusinessException.class, () ->
                knowledgeBaseService.uploadDocument("testuser", knowledgeBase.getExternalId(), file, ChunkStrategy.PARAGRAPH));

        assertEquals("VALIDATION_ERROR", exception.getErrorCode());
    }

    @Test
    void uploadDocument_FileTooLarge_ThrowsKnowledgeBaseFriendlyMessage() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "large.pdf",
                "application/pdf",
                new byte[10 * 1024 * 1024 + 1]
        );

        when(knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);

        BusinessException exception = assertThrows(BusinessException.class, () -> knowledgeBaseService.uploadDocument(
                "testuser",
                knowledgeBase.getExternalId(),
                file,
                ChunkStrategy.FIXED_SIZE
        ));

        assertEquals("文件大小超过限制", exception.getMessage());
    }

    @Test
    void uploadDocument_LockedKnowledgeBaseDeleted_ThrowsNotFoundBeforeStoringFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "redis.pdf",
                "application/pdf",
                "hello kb".getBytes()
        );
        KnowledgeBase lockedKnowledgeBase = KnowledgeBase.builder()
                .id(knowledgeBase.getId())
                .externalId(knowledgeBase.getExternalId())
                .userId(knowledgeBase.getUserId())
                .name(knowledgeBase.getName())
                .status(KnowledgeBaseStatus.DELETED)
                .build();

        when(knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(Optional.of(lockedKnowledgeBase));

        BusinessException exception = assertThrows(BusinessException.class, () -> knowledgeBaseService.uploadDocument(
                "testuser",
                knowledgeBase.getExternalId(),
                file,
                ChunkStrategy.FIXED_SIZE
        ));

        assertEquals(ErrorCode.KB_001, exception.getErrorCode());
        verify(knowledgeBaseStorageService, never()).store(any());
    }

    @Test
    void uploadDocument_LockedKnowledgeBaseArchived_ThrowsConflictBeforeStoringFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "redis.pdf",
                "application/pdf",
                "hello kb".getBytes()
        );
        KnowledgeBase lockedKnowledgeBase = KnowledgeBase.builder()
                .id(knowledgeBase.getId())
                .externalId(knowledgeBase.getExternalId())
                .userId(knowledgeBase.getUserId())
                .name(knowledgeBase.getName())
                .status(KnowledgeBaseStatus.ARCHIVED)
                .build();

        when(knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(Optional.of(lockedKnowledgeBase));

        BusinessException exception = assertThrows(BusinessException.class, () -> knowledgeBaseService.uploadDocument(
                "testuser",
                knowledgeBase.getExternalId(),
                file,
                ChunkStrategy.FIXED_SIZE
        ));

        assertEquals(ErrorCode.KB_003, exception.getErrorCode());
        verify(knowledgeBaseStorageService, never()).store(any());
    }

    @Test
    void getDocumentDetail_ReturnsArtifactSummaryWhenPresent() throws Exception {
        KbDocumentArtifact artifact = KbDocumentArtifact.builder()
                .documentId(kbDocument.getId())
                .artifactType(KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT)
                .metadata("{\"reviewOnlyBlockCount\":2}")
                .updatedAt(LocalDateTime.now())
                .build();

        when(knowledgeBaseAccessService.requireReadableDocument("testuser", knowledgeBase.getExternalId(), kbDocument.getExternalId()))
                .thenReturn(kbDocument);
        when(kbDocumentArtifactService.findArtifact(kbDocument.getId(), KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT))
                .thenReturn(Optional.of(artifact));
        when(objectMapper.readValue(artifact.getMetadata(), Map.class)).thenReturn(Map.of("reviewOnlyBlockCount", 2));

        KbDocumentDetailResponse response = knowledgeBaseService.getDocumentDetail(
                "testuser",
                knowledgeBase.getExternalId(),
                kbDocument.getExternalId()
        );

        assertEquals(kbDocument.getExternalId(), response.getId());
        assertEquals(KbDocumentStatus.COMPLETED, response.getStatus());
        assertEquals(KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT, response.getArtifactSummary().getArtifactType());
        assertEquals(true, response.getArtifactSummary().getExists());
        assertEquals(2, response.getArtifactSummary().getMetadata().get("reviewOnlyBlockCount"));
    }

    @Test
    void getDocumentArtifact_FailedDocumentWithArtifact_RemainsReadable() throws Exception {
        kbDocument.setStatus(KbDocumentStatus.FAILED);
        KbDocumentArtifact artifact = KbDocumentArtifact.builder()
                .documentId(kbDocument.getId())
                .artifactType(KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT)
                .content("[BLOCK]")
                .metadata("{\"reviewOnlyBlockCount\":2}")
                .updatedAt(LocalDateTime.now())
                .build();

        when(knowledgeBaseAccessService.requireReadableDocument("testuser", knowledgeBase.getExternalId(), kbDocument.getExternalId()))
                .thenReturn(kbDocument);
        when(kbDocumentArtifactService.findArtifact(kbDocument.getId(), KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT))
                .thenReturn(Optional.of(artifact));
        when(objectMapper.readValue(artifact.getMetadata(), Map.class)).thenReturn(Map.of("reviewOnlyBlockCount", 2));

        KbDocumentArtifactResponse response = knowledgeBaseService.getDocumentArtifact(
                "testuser",
                knowledgeBase.getExternalId(),
                kbDocument.getExternalId(),
                KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT
        );

        assertEquals(KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT, response.getArtifactType());
        assertEquals("[BLOCK]", response.getContent());
        assertEquals(true, response.getExists());
    }

    @Test
    void getDocumentArtifact_ProcessingDocumentWithArtifact_RemainsReadable() throws Exception {
        kbDocument.setStatus(KbDocumentStatus.PROCESSING);
        KbDocumentArtifact artifact = KbDocumentArtifact.builder()
                .documentId(kbDocument.getId())
                .artifactType(KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT)
                .content("[BLOCK]")
                .metadata("{\"reviewOnlyBlockCount\":1}")
                .updatedAt(LocalDateTime.now())
                .build();

        when(knowledgeBaseAccessService.requireReadableDocument("testuser", knowledgeBase.getExternalId(), kbDocument.getExternalId()))
                .thenReturn(kbDocument);
        when(kbDocumentArtifactService.findArtifact(kbDocument.getId(), KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT))
                .thenReturn(Optional.of(artifact));
        when(objectMapper.readValue(artifact.getMetadata(), Map.class)).thenReturn(Map.of("reviewOnlyBlockCount", 1));

        KbDocumentArtifactResponse response = knowledgeBaseService.getDocumentArtifact(
                "testuser",
                knowledgeBase.getExternalId(),
                kbDocument.getExternalId(),
                KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT
        );

        assertEquals("[BLOCK]", response.getContent());
        assertEquals(1, response.getMetadata().get("reviewOnlyBlockCount"));
    }
}
