package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.knowledgebase.dto.response.KnowledgeBaseStatsResponse;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Builds live statistics snapshots for knowledge bases from document and chat tables.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseStatsService {

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final KbDocumentRepository kbDocumentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * Aggregates the current document, chunk, size, and query metrics for the target knowledge base.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @return live statistics snapshot
     */
    public KnowledgeBaseStatsResponse getStats(String username, UUID kbExternalId) {
        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireReadableKnowledgeBase(username, kbExternalId);
        return KnowledgeBaseStatsResponse.builder()
                .totalDocuments(Math.toIntExact(kbDocumentRepository.countByKbId(knowledgeBase.getId())))
                .totalChunks(Math.toIntExact(documentChunkRepository.countByKbId(knowledgeBase.getId())))
                .totalSize(kbDocumentRepository.sumFileSizeByKbId(knowledgeBase.getId()))
                .pendingDocuments(Math.toIntExact(kbDocumentRepository.countByKbIdAndStatus(knowledgeBase.getId(), KbDocumentStatus.PENDING)))
                .processingDocuments(Math.toIntExact(kbDocumentRepository.countByKbIdAndStatus(knowledgeBase.getId(), KbDocumentStatus.PROCESSING)))
                .failedDocuments(Math.toIntExact(kbDocumentRepository.countByKbIdAndStatus(knowledgeBase.getId(), KbDocumentStatus.FAILED)))
                .totalQueries(chatMessageRepository.countAssistantAnswersByKnowledgeBase(knowledgeBase.getExternalId()))
                .averageConfidence(chatMessageRepository.averageAssistantConfidenceByKnowledgeBase(knowledgeBase.getExternalId()))
                .indexingStatus(knowledgeBase.getIndexingStatus())
                .build();
    }
}
