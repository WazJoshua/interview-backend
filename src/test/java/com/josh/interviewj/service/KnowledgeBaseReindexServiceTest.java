package com.josh.interviewj.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentArtifactRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseAccessService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseEmbeddingConfigService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseReindexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseReindexServiceTest {

    @Mock
    private KnowledgeBaseAccessService knowledgeBaseAccessService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private KbDocumentRepository kbDocumentRepository;

    @Mock
    private KbDocumentOutboxRepository kbDocumentOutboxRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private KbDocumentArtifactRepository kbDocumentArtifactRepository;

    @Mock
    private KnowledgeBaseEmbeddingConfigService knowledgeBaseEmbeddingConfigService;

    @InjectMocks
    private KnowledgeBaseReindexService knowledgeBaseReindexService;

    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = KnowledgeBase.builder()
                .id(1L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .name("KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .build();
    }

    @Test
    void reindex_WhenAlreadyReindexing_ReturnsAcceptedSnapshot() {
        when(knowledgeBaseAccessService.requireReindexableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        KnowledgeBase locked = KnowledgeBase.builder()
                .id(knowledgeBase.getId())
                .externalId(knowledgeBase.getExternalId())
                .userId(knowledgeBase.getUserId())
                .status(KnowledgeBaseStatus.ACTIVE)
                .indexingStatus(KnowledgeBaseIndexingStatus.REINDEXING)
                .build();
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId())).thenReturn(Optional.of(locked));
        when(kbDocumentRepository.countByKbId(knowledgeBase.getId())).thenReturn(2L);

        var response = knowledgeBaseReindexService.reindex("testuser", knowledgeBase.getExternalId());

        assertEquals("ACCEPTED", response.getStatus());
        assertEquals(KnowledgeBaseIndexingStatus.REINDEXING, response.getIndexingStatus());
        verify(kbDocumentRepository, never()).save(any());
    }

    @Test
    void reindex_WhenLockedDocumentsContainProcessing_ThrowsConflict() {
        when(knowledgeBaseAccessService.requireReindexableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId())).thenReturn(Optional.of(knowledgeBase));
        when(kbDocumentRepository.findAllByKbIdOrderByIdAsc(knowledgeBase.getId()))
                .thenReturn(List.of(buildDocument(10L, KbDocumentStatus.PROCESSING)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> knowledgeBaseReindexService.reindex("testuser", knowledgeBase.getExternalId()));

        assertEquals(ErrorCode.KB_003, exception.getErrorCode());
    }

    @Test
    void reindex_ResetsAllDocumentsAndCreatesFreshOutbox() {
        KbDocument first = buildDocument(10L);
        KbDocument second = buildDocument(11L);
        when(knowledgeBaseAccessService.requireReindexableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId())).thenReturn(Optional.of(knowledgeBase));
        when(knowledgeBaseEmbeddingConfigService.getCurrentDocumentEmbedding())
                .thenReturn(new KnowledgeBaseEmbeddingConfigService.KnowledgeBaseEmbeddingConfig("configured-embedding-v2", 3072));
        when(kbDocumentRepository.findAllByKbIdOrderByIdAsc(knowledgeBase.getId())).thenReturn(List.of(first, second));
        when(kbDocumentRepository.countByKbIdAndStatusIn(knowledgeBase.getId(), List.of(KbDocumentStatus.PENDING, KbDocumentStatus.PROCESSING)))
                .thenReturn(2L);

        var response = knowledgeBaseReindexService.reindex("testuser", knowledgeBase.getExternalId());

        assertEquals("ACCEPTED", response.getStatus());
        assertEquals(KnowledgeBaseIndexingStatus.REINDEXING, knowledgeBase.getIndexingStatus());
        assertEquals("configured-embedding-v2", knowledgeBase.getEmbeddingModel());
        assertEquals(3072, knowledgeBase.getVectorDimension());
        assertEquals(0, first.getChunkCount());
        assertEquals(0, second.getExpectedChunkCount());
        verify(kbDocumentOutboxRepository).deleteByDocumentId(first.getId());
        verify(documentChunkRepository).deleteByDocumentId(second.getId());
        verify(kbDocumentArtifactRepository).deleteByDocumentId(first.getId());
        verify(kbDocumentRepository).save(first);
        verify(kbDocumentRepository).save(second);

        ArgumentCaptor<KbDocumentOutbox> outboxCaptor = ArgumentCaptor.forClass(KbDocumentOutbox.class);
        verify(kbDocumentOutboxRepository, org.mockito.Mockito.times(2)).save(outboxCaptor.capture());
        assertEquals(2, outboxCaptor.getAllValues().size());
    }

    @Test
    void maybeCompleteReindex_NoPendingOrProcessing_ClearsIndexingStatus() {
        knowledgeBase.setIndexingStatus(KnowledgeBaseIndexingStatus.REINDEXING);
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId())).thenReturn(Optional.of(knowledgeBase));
        when(kbDocumentRepository.countByKbIdAndStatusIn(knowledgeBase.getId(), List.of(KbDocumentStatus.PENDING, KbDocumentStatus.PROCESSING)))
                .thenReturn(0L);

        knowledgeBaseReindexService.maybeCompleteReindex(knowledgeBase.getId());

        assertNull(knowledgeBase.getIndexingStatus());
        verify(knowledgeBaseRepository).save(knowledgeBase);
    }

    private KbDocument buildDocument(Long id) {
        return buildDocument(id, KbDocumentStatus.COMPLETED);
    }

    private KbDocument buildDocument(Long id, KbDocumentStatus status) {
        return KbDocument.builder()
                .id(id)
                .kbId(knowledgeBase.getId())
                .externalId(UUID.randomUUID())
                .chunkCount(3)
                .expectedChunkCount(3)
                .embeddedChunkCount(3)
                .status(status)
                .sparseReadyVersion("HYBRID_SPARSE_V1")
                .build();
    }
}
