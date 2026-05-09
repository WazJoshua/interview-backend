package com.josh.interviewj.service;

import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseAccessService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseStatsServiceTest {

    @Mock
    private KnowledgeBaseAccessService knowledgeBaseAccessService;

    @Mock
    private KbDocumentRepository kbDocumentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @InjectMocks
    private KnowledgeBaseStatsService knowledgeBaseStatsService;

    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = KnowledgeBase.builder()
                .id(1L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .name("Java KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .indexingStatus(KnowledgeBaseIndexingStatus.REINDEXING)
                .build();
    }

    @Test
    void getStats_UsesLiveAggregations() {
        when(knowledgeBaseAccessService.requireReadableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(kbDocumentRepository.countByKbId(knowledgeBase.getId())).thenReturn(4L);
        when(documentChunkRepository.countByKbId(knowledgeBase.getId())).thenReturn(15L);
        when(kbDocumentRepository.sumFileSizeByKbId(knowledgeBase.getId())).thenReturn(4096L);
        when(kbDocumentRepository.countByKbIdAndStatus(knowledgeBase.getId(), KbDocumentStatus.PENDING)).thenReturn(1L);
        when(kbDocumentRepository.countByKbIdAndStatus(knowledgeBase.getId(), KbDocumentStatus.PROCESSING)).thenReturn(2L);
        when(kbDocumentRepository.countByKbIdAndStatus(knowledgeBase.getId(), KbDocumentStatus.FAILED)).thenReturn(1L);
        when(chatMessageRepository.countAssistantAnswersByKnowledgeBase(knowledgeBase.getExternalId())).thenReturn(7L);
        when(chatMessageRepository.averageAssistantConfidenceByKnowledgeBase(knowledgeBase.getExternalId())).thenReturn(0.73D);

        var response = knowledgeBaseStatsService.getStats("testuser", knowledgeBase.getExternalId());

        assertEquals(4, response.getTotalDocuments());
        assertEquals(15, response.getTotalChunks());
        assertEquals(4096L, response.getTotalSize());
        assertEquals(1, response.getPendingDocuments());
        assertEquals(2, response.getProcessingDocuments());
        assertEquals(1, response.getFailedDocuments());
        assertEquals(7L, response.getTotalQueries());
        assertEquals(0.73D, response.getAverageConfidence());
        assertEquals(KnowledgeBaseIndexingStatus.REINDEXING, response.getIndexingStatus());
    }

    @Test
    void getStats_NoConfidenceSamples_ReturnsNullAverageConfidence() {
        when(knowledgeBaseAccessService.requireReadableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
        when(kbDocumentRepository.countByKbId(knowledgeBase.getId())).thenReturn(0L);
        when(documentChunkRepository.countByKbId(knowledgeBase.getId())).thenReturn(0L);
        when(kbDocumentRepository.sumFileSizeByKbId(knowledgeBase.getId())).thenReturn(0L);
        when(kbDocumentRepository.countByKbIdAndStatus(knowledgeBase.getId(), KbDocumentStatus.PENDING)).thenReturn(0L);
        when(kbDocumentRepository.countByKbIdAndStatus(knowledgeBase.getId(), KbDocumentStatus.PROCESSING)).thenReturn(0L);
        when(kbDocumentRepository.countByKbIdAndStatus(knowledgeBase.getId(), KbDocumentStatus.FAILED)).thenReturn(0L);
        when(chatMessageRepository.countAssistantAnswersByKnowledgeBase(knowledgeBase.getExternalId())).thenReturn(0L);
        when(chatMessageRepository.averageAssistantConfidenceByKnowledgeBase(knowledgeBase.getExternalId())).thenReturn(null);

        var response = knowledgeBaseStatsService.getStats("testuser", knowledgeBase.getExternalId());

        assertEquals(0L, response.getTotalQueries());
        assertNull(response.getAverageConfidence());
    }
}
