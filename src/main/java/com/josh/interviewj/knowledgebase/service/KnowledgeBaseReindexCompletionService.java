package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Finalizes KB-level reindex status in its own transaction after document completion commits.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseReindexCompletionService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbDocumentRepository kbDocumentRepository;

    /**
     * Clears the KB indexing status once no pending or processing documents remain.
     *
     * @param kbId knowledge base primary key
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeIfIdle(Long kbId) {
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
}
