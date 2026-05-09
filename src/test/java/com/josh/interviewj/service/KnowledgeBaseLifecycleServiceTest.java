package com.josh.interviewj.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.dto.request.KnowledgeBaseUpdateRequest;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentArtifactRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseAccessService;
import com.josh.interviewj.knowledgebase.service.KbFileCleanupService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseLifecycleServiceTest {

    @Mock
    private KnowledgeBaseAccessService knowledgeBaseAccessService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private KbDocumentRepository kbDocumentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private KbDocumentArtifactRepository kbDocumentArtifactRepository;

    @Mock
    private KbDocumentOutboxRepository kbDocumentOutboxRepository;

    @Mock
    private KbFileCleanupService kbFileCleanupService;

    @InjectMocks
    private KnowledgeBaseLifecycleService knowledgeBaseLifecycleService;

    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = KnowledgeBase.builder()
                .id(1L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .name("Java KB")
                .description("Spring docs")
                .embeddingModel("text-embedding-v4")
                .vectorDimension(2048)
                .documentCount(2)
                .totalChunks(10)
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void updateKnowledgeBase_NameOnly_UpdatesEntity() {
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName("Renamed KB");
        when(knowledgeBaseAccessService.requireWritableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(knowledgeBase));
        when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = knowledgeBaseLifecycleService.updateKnowledgeBase("testuser", knowledgeBase.getExternalId(), request);

        assertEquals("Renamed KB", response.getName());
        assertEquals("Spring docs", response.getDescription());
        verify(knowledgeBaseRepository).save(knowledgeBase);
    }

    @Test
    void updateKnowledgeBase_DescriptionOnly_UpdatesEntity() {
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setDescription("Updated description");
        when(knowledgeBaseAccessService.requireWritableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(knowledgeBase));
        when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = knowledgeBaseLifecycleService.updateKnowledgeBase("testuser", knowledgeBase.getExternalId(), request);

        assertEquals("Java KB", response.getName());
        assertEquals("Updated description", response.getDescription());
    }

    @Test
    void updateKnowledgeBase_NoFieldProvided_ThrowsValidationError() {
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseLifecycleService.updateKnowledgeBase("testuser", knowledgeBase.getExternalId(), request));

        assertEquals("VALIDATION_ERROR", exception.getErrorCode());
    }

    @Test
    void updateKnowledgeBase_NoActualChange_ThrowsConflict() {
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName("Java KB");
        when(knowledgeBaseAccessService.requireWritableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(knowledgeBase));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseLifecycleService.updateKnowledgeBase("testuser", knowledgeBase.getExternalId(), request));

        assertEquals(ErrorCode.KB_003, exception.getErrorCode());
    }

    @Test
    void updateKnowledgeBase_ArchivedKnowledgeBase_PropagatesConflict() {
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName("Renamed KB");
        when(knowledgeBaseAccessService.requireWritableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenThrow(new BusinessException(ErrorCode.KB_003, "知识库当前状态不允许此操作"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseLifecycleService.updateKnowledgeBase("testuser", knowledgeBase.getExternalId(), request));

        assertEquals(ErrorCode.KB_003, exception.getErrorCode());
    }

    @Test
    void updateKnowledgeBase_UsesLockedKnowledgeBaseState() {
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName("Renamed KB");
        KnowledgeBase lockedKnowledgeBase = KnowledgeBase.builder()
                .id(knowledgeBase.getId())
                .externalId(knowledgeBase.getExternalId())
                .userId(knowledgeBase.getUserId())
                .name("Java KB")
                .status(KnowledgeBaseStatus.ARCHIVED)
                .build();
        when(knowledgeBaseAccessService.requireWritableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(lockedKnowledgeBase));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseLifecycleService.updateKnowledgeBase("testuser", knowledgeBase.getExternalId(), request));

        assertEquals(ErrorCode.KB_003, exception.getErrorCode());
        verify(knowledgeBaseRepository, never()).save(any());
    }

    @Test
    void deleteDocument_CompletedDocument_DeletesDataAndSchedulesCleanup() {
        KbDocument document = KbDocument.builder()
                .id(10L)
                .kbId(knowledgeBase.getId())
                .externalId(UUID.randomUUID())
                .fileUrl("kb/doc.pdf")
                .status(KbDocumentStatus.COMPLETED)
                .build();
        when(knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(knowledgeBase));
        when(kbDocumentRepository.findByExternalIdAndKbIdForUpdate(document.getExternalId(), knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(document));
        when(documentChunkRepository.countByDocumentId(document.getId())).thenReturn(4L);
        when(kbFileCleanupService.enqueueDocumentFile(document))
                .thenReturn(List.of(com.josh.interviewj.knowledgebase.model.KbFileCleanupTask.builder().id(101L).build()));

        knowledgeBaseLifecycleService.deleteDocument("testuser", knowledgeBase.getExternalId(), document.getExternalId());

        verify(kbFileCleanupService).enqueueDocumentFile(document);
        verify(kbDocumentArtifactRepository).deleteByDocumentId(document.getId());
        verify(documentChunkRepository).deleteByDocumentId(document.getId());
        verify(kbDocumentOutboxRepository).deleteByDocumentId(document.getId());
        verify(kbDocumentRepository).delete(document);
        verify(knowledgeBaseRepository).decrementDocumentCount(knowledgeBase.getId(), 1);
        verify(knowledgeBaseRepository).decrementTotalChunks(knowledgeBase.getId(), 4);
        verify(kbFileCleanupService).scheduleDrainAfterCommit(List.of(101L));
    }

    @Test
    void deleteDocument_FailedDocumentWithResidualChunks_DoesNotDecrementTotalChunks() {
        KbDocument document = KbDocument.builder()
                .id(10L)
                .kbId(knowledgeBase.getId())
                .externalId(UUID.randomUUID())
                .fileUrl("kb/doc.pdf")
                .status(KbDocumentStatus.FAILED)
                .build();
        when(knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(knowledgeBase));
        when(kbDocumentRepository.findByExternalIdAndKbIdForUpdate(document.getExternalId(), knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(document));
        when(documentChunkRepository.countByDocumentId(document.getId())).thenReturn(4L);
        when(kbFileCleanupService.enqueueDocumentFile(document))
                .thenReturn(List.of(com.josh.interviewj.knowledgebase.model.KbFileCleanupTask.builder().id(102L).build()));

        knowledgeBaseLifecycleService.deleteDocument("testuser", knowledgeBase.getExternalId(), document.getExternalId());

        verify(knowledgeBaseRepository).decrementDocumentCount(knowledgeBase.getId(), 1);
        verify(knowledgeBaseRepository, never()).decrementTotalChunks(any(), any());
        verify(kbFileCleanupService).scheduleDrainAfterCommit(List.of(102L));
    }

    @Test
    void deleteDocument_ProcessingDocument_ThrowsConflict() {
        KbDocument document = KbDocument.builder()
                .id(10L)
                .kbId(knowledgeBase.getId())
                .externalId(UUID.randomUUID())
                .status(KbDocumentStatus.PROCESSING)
                .build();
        when(knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(knowledgeBase));
        when(kbDocumentRepository.findByExternalIdAndKbIdForUpdate(document.getExternalId(), knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(document));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseLifecycleService.deleteDocument("testuser", knowledgeBase.getExternalId(), document.getExternalId()));

        assertEquals(ErrorCode.KB_003, exception.getErrorCode());
    }

    @Test
    void deleteDocument_UsesLockedKnowledgeBaseState() {
        KbDocument document = KbDocument.builder()
                .id(10L)
                .kbId(knowledgeBase.getId())
                .externalId(UUID.randomUUID())
                .status(KbDocumentStatus.COMPLETED)
                .build();
        KnowledgeBase lockedKnowledgeBase = KnowledgeBase.builder()
                .id(knowledgeBase.getId())
                .externalId(knowledgeBase.getExternalId())
                .userId(knowledgeBase.getUserId())
                .name(knowledgeBase.getName())
                .status(KnowledgeBaseStatus.ACTIVE)
                .indexingStatus(KnowledgeBaseIndexingStatus.REINDEXING)
                .build();
        when(knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(lockedKnowledgeBase));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseLifecycleService.deleteDocument("testuser", knowledgeBase.getExternalId(), document.getExternalId()));

        assertEquals(ErrorCode.KB_003, exception.getErrorCode());
        verify(kbDocumentRepository, never()).findByExternalIdAndKbIdForUpdate(any(), any());
    }

    @Test
    void deleteKnowledgeBase_DeletesChildrenAndMarksDeleted() {
        KbDocument document = KbDocument.builder()
                .id(10L)
                .kbId(knowledgeBase.getId())
                .externalId(UUID.randomUUID())
                .fileUrl("kb/doc.pdf")
                .status(KbDocumentStatus.COMPLETED)
                .build();
        when(knowledgeBaseAccessService.requireDeletableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(knowledgeBase));
        when(kbDocumentRepository.findAllByKbIdOrderByIdAsc(knowledgeBase.getId()))
                .thenReturn(java.util.List.of(document));
        when(kbFileCleanupService.enqueueKnowledgeBaseFiles(knowledgeBase.getId(), java.util.List.of(document)))
                .thenReturn(List.of(com.josh.interviewj.knowledgebase.model.KbFileCleanupTask.builder().id(201L).build()));

        knowledgeBaseLifecycleService.deleteKnowledgeBase("testuser", knowledgeBase.getExternalId());

        assertEquals(KnowledgeBaseStatus.DELETED, knowledgeBase.getStatus());
        verify(kbFileCleanupService).enqueueKnowledgeBaseFiles(knowledgeBase.getId(), java.util.List.of(document));
        verify(kbDocumentArtifactRepository).deleteByKbId(knowledgeBase.getId());
        verify(documentChunkRepository).deleteByKbId(knowledgeBase.getId());
        verify(kbDocumentOutboxRepository).deleteByKbId(knowledgeBase.getId());
        verify(kbDocumentRepository).deleteByKbId(knowledgeBase.getId());
        verify(knowledgeBaseRepository).save(knowledgeBase);
        verify(kbFileCleanupService).scheduleDrainAfterCommit(List.of(201L));
    }

    @Test
    void deleteKnowledgeBase_ReindexingKnowledgeBase_ThrowsConflict() {
        knowledgeBase.setIndexingStatus(KnowledgeBaseIndexingStatus.REINDEXING);
        when(knowledgeBaseAccessService.requireDeletableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(knowledgeBase));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseLifecycleService.deleteKnowledgeBase("testuser", knowledgeBase.getExternalId()));

        assertEquals(ErrorCode.KB_003, exception.getErrorCode());
    }

    @Test
    void deleteKnowledgeBase_UsesLockedKnowledgeBaseStateForBusyCheck() {
        KnowledgeBase lockedKnowledgeBase = KnowledgeBase.builder()
                .id(knowledgeBase.getId())
                .externalId(knowledgeBase.getExternalId())
                .userId(knowledgeBase.getUserId())
                .name(knowledgeBase.getName())
                .status(KnowledgeBaseStatus.ACTIVE)
                .indexingStatus(KnowledgeBaseIndexingStatus.REINDEXING)
                .build();
        when(knowledgeBaseAccessService.requireDeletableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(lockedKnowledgeBase));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseLifecycleService.deleteKnowledgeBase("testuser", knowledgeBase.getExternalId()));

        assertEquals(ErrorCode.KB_003, exception.getErrorCode());
        verify(kbDocumentRepository, never()).deleteByKbId(any());
    }

    @Test
    void deleteKnowledgeBase_UsesLockedDocumentsForProcessingCheck() {
        KbDocument processingDocument = KbDocument.builder()
                .id(10L)
                .kbId(knowledgeBase.getId())
                .externalId(UUID.randomUUID())
                .status(KbDocumentStatus.PROCESSING)
                .build();
        when(knowledgeBaseAccessService.requireDeletableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId()))
                .thenReturn(java.util.Optional.of(knowledgeBase));
        when(kbDocumentRepository.findAllByKbIdOrderByIdAsc(knowledgeBase.getId()))
                .thenReturn(java.util.List.of(processingDocument));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseLifecycleService.deleteKnowledgeBase("testuser", knowledgeBase.getExternalId()));

        assertEquals(ErrorCode.KB_003, exception.getErrorCode());
        verify(kbDocumentRepository, never()).deleteByKbId(any());
    }
}
