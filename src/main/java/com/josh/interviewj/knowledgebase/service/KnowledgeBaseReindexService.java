package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.dto.response.KnowledgeBaseReindexResponse;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentArtifactRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Resets knowledge base documents for re-ingestion and tracks reindex completion.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseReindexService {

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbDocumentRepository kbDocumentRepository;
    private final KbDocumentOutboxRepository kbDocumentOutboxRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final KbDocumentArtifactRepository kbDocumentArtifactRepository;
    private final KnowledgeBaseEmbeddingConfigService knowledgeBaseEmbeddingConfigService;

    /**
     * Rebuilds all documents under the target knowledge base inside one transaction.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @return accepted snapshot for the reindex request
     */
    @Transactional
    public KnowledgeBaseReindexResponse reindex(String username, UUID kbExternalId) {
        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireReindexableKnowledgeBase(username, kbExternalId);
        KnowledgeBase locked = knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_001, "知识库不存在"));
        if (locked.getIndexingStatus() == KnowledgeBaseIndexingStatus.REINDEXING) {
            return toAcceptedResponse(locked, Math.toIntExact(kbDocumentRepository.countByKbId(locked.getId())));
        }
        List<KbDocument> documents = kbDocumentRepository.findAllByKbIdOrderByIdAsc(locked.getId());
        boolean hasProcessingDocument = documents.stream()
                .anyMatch(document -> document.getStatus() == KbDocumentStatus.PROCESSING);
        if (hasProcessingDocument) {
            throw new BusinessException(ErrorCode.KB_003, "知识库当前状态不允许此操作");
        }
        KnowledgeBaseEmbeddingConfigService.KnowledgeBaseEmbeddingConfig embeddingConfig =
                knowledgeBaseEmbeddingConfigService.getCurrentDocumentEmbedding();
        locked.setEmbeddingModel(embeddingConfig.model());
        locked.setVectorDimension(embeddingConfig.dimension());
        locked.setIndexingStatus(KnowledgeBaseIndexingStatus.REINDEXING);
        locked.setTotalChunks(0);
        knowledgeBaseRepository.save(locked);

        for (KbDocument document : documents) {
            kbDocumentOutboxRepository.deleteByDocumentId(document.getId());
            documentChunkRepository.deleteByDocumentId(document.getId());
            kbDocumentArtifactRepository.deleteByDocumentId(document.getId());
            document.setChunkCount(0);
            document.setExpectedChunkCount(0);
            document.setEmbeddedChunkCount(0);
            document.setProcessedAt(null);
            document.setErrorMessage(null);
            document.setSparseReadyVersion(null);
            document.setSparseReadyAt(null);
            document.setStatus(KbDocumentStatus.PENDING);
            kbDocumentRepository.save(document);
            kbDocumentOutboxRepository.save(KbDocumentOutbox.builder()
                    .kbId(document.getKbId())
                    .documentId(document.getId())
                    .status(OutboxStatus.NEW)
                    .retryCount(0)
                    .build());
        }

        maybeCompleteReindex(locked.getId());
        return toAcceptedResponse(locked, documents.size());
    }

    /**
     * Clears the KB indexing status when no pending or processing documents remain.
     *
     * @param kbId knowledge base primary key
     */
    @Transactional
    public void maybeCompleteReindex(Long kbId) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByIdForUpdate(kbId)
                .orElse(null);
        if (knowledgeBase == null || knowledgeBase.getIndexingStatus() != KnowledgeBaseIndexingStatus.REINDEXING) {
            return;
        }
        long inFlightCount = kbDocumentRepository.countByKbIdAndStatusIn(
                kbId,
                List.of(KbDocumentStatus.PENDING, KbDocumentStatus.PROCESSING)
        );
        if (inFlightCount == 0) {
            knowledgeBase.setIndexingStatus(null);
            knowledgeBaseRepository.save(knowledgeBase);
        }
    }

    /**
     * Creates the accepted response payload exposed by the reindex endpoint.
     *
     * @param knowledgeBase locked knowledge base entity
     * @param totalDocuments number of documents included in this reset
     * @return accepted response snapshot
     */
    private KnowledgeBaseReindexResponse toAcceptedResponse(KnowledgeBase knowledgeBase, Integer totalDocuments) {
        return KnowledgeBaseReindexResponse.builder()
                .kbId(knowledgeBase.getExternalId())
                .totalDocuments(totalDocuments)
                .status("ACCEPTED")
                .indexingStatus(KnowledgeBaseIndexingStatus.REINDEXING)
                .build();
    }
}
