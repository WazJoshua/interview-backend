package com.josh.interviewj.service;

import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseReindexCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseReindexCompletionServiceTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private KbDocumentRepository kbDocumentRepository;

    @InjectMocks
    private KnowledgeBaseReindexCompletionService knowledgeBaseReindexCompletionService;

    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = KnowledgeBase.builder()
                .id(1L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .name("KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .indexingStatus(KnowledgeBaseIndexingStatus.REINDEXING)
                .build();
    }

    @Test
    void completeIfIdle_NoPendingOrProcessing_ClearsIndexingStatus() {
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId())).thenReturn(Optional.of(knowledgeBase));
        when(kbDocumentRepository.countByKbIdAndStatusIn(knowledgeBase.getId(), List.of(KbDocumentStatus.PENDING, KbDocumentStatus.PROCESSING)))
                .thenReturn(0L);

        knowledgeBaseReindexCompletionService.completeIfIdle(knowledgeBase.getId());

        assertNull(knowledgeBase.getIndexingStatus());
        verify(knowledgeBaseRepository).save(knowledgeBase);
    }

    @Test
    void completeIfIdle_WhenPendingDocumentsRemain_LeavesIndexingStatusUntouched() {
        when(knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId())).thenReturn(Optional.of(knowledgeBase));
        when(kbDocumentRepository.countByKbIdAndStatusIn(knowledgeBase.getId(), List.of(KbDocumentStatus.PENDING, KbDocumentStatus.PROCESSING)))
                .thenReturn(1L);

        knowledgeBaseReindexCompletionService.completeIfIdle(knowledgeBase.getId());

        assertEquals(KnowledgeBaseIndexingStatus.REINDEXING, knowledgeBase.getIndexingStatus());
        verify(knowledgeBaseRepository, never()).save(knowledgeBase);
    }

    @Test
    void completeIfIdle_UsesRequiresNewTransaction() throws NoSuchMethodException {
        Transactional transactional = KnowledgeBaseReindexCompletionService.class
                .getMethod("completeIfIdle", Long.class)
                .getAnnotation(Transactional.class);

        assertTrue(transactional != null && transactional.propagation() == Propagation.REQUIRES_NEW);
    }
}
