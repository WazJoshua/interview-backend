package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.knowledgebase.dto.request.KnowledgeBaseUpdateRequest;
import com.josh.interviewj.knowledgebase.dto.response.KnowledgeBaseResponse;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentArtifactRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Handles mutable knowledge base lifecycle operations such as update and delete flows.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseLifecycleService {

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbDocumentRepository kbDocumentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final KbDocumentArtifactRepository kbDocumentArtifactRepository;
    private final KbDocumentOutboxRepository kbDocumentOutboxRepository;
    private final KbFileCleanupService kbFileCleanupService;

    /**
     * Updates mutable knowledge base fields on a locked knowledge base row.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @param request partial update payload
     * @return updated knowledge base response
     */
    @Transactional
    public KnowledgeBaseResponse updateKnowledgeBase(String username, UUID kbExternalId, KnowledgeBaseUpdateRequest request) {
        if (request == null || !request.hasAnyFieldProvided()) {
            throw new BusinessException("VALIDATION_ERROR", "At least one updatable field must be provided");
        }

        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireWritableKnowledgeBase(username, kbExternalId);
        KnowledgeBase lockedKnowledgeBase = knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_001, "知识库不存在"));
        validateLockedWritableKnowledgeBase(lockedKnowledgeBase);
        boolean changed = false;

        if (request.getName() != null && !request.getName().isBlank()) {
            String normalizedName = request.getName().trim();
            if (!normalizedName.equals(lockedKnowledgeBase.getName())) {
                lockedKnowledgeBase.setName(normalizedName);
                changed = true;
            }
        }

        if (request.getDescription() != null && !request.getDescription().isBlank()) {
            String normalizedDescription = request.getDescription().trim();
            if (!normalizedDescription.equals(lockedKnowledgeBase.getDescription())) {
                lockedKnowledgeBase.setDescription(normalizedDescription);
                changed = true;
            }
        }

        if (!changed) {
            throw new BusinessException(ErrorCode.KB_003, "知识库内容没有变化");
        }

        return toResponse(knowledgeBaseRepository.save(lockedKnowledgeBase));
    }

    /**
     * Soft-deletes the knowledge base and hard-cleans child resources after all busy-state checks pass.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     */
    @Transactional
    public void deleteKnowledgeBase(String username, UUID kbExternalId) {
        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireDeletableKnowledgeBase(username, kbExternalId);
        KnowledgeBase lockedKnowledgeBase = knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_001, "知识库不存在"));
        validateLockedDeletableKnowledgeBase(lockedKnowledgeBase);

        List<KbDocument> documents = kbDocumentRepository.findAllByKbIdOrderByIdAsc(lockedKnowledgeBase.getId());
        boolean hasProcessingDocument = documents.stream()
                .anyMatch(document -> document.getStatus() == KbDocumentStatus.PROCESSING);
        if (lockedKnowledgeBase.getIndexingStatus() == KnowledgeBaseIndexingStatus.REINDEXING || hasProcessingDocument) {
            throw new BusinessException(ErrorCode.KB_003, "知识库当前状态不允许此操作");
        }

        List<com.josh.interviewj.knowledgebase.model.KbFileCleanupTask> cleanupTasks =
                kbFileCleanupService.enqueueKnowledgeBaseFiles(lockedKnowledgeBase.getId(), documents);
        kbDocumentArtifactRepository.deleteByKbId(lockedKnowledgeBase.getId());
        documentChunkRepository.deleteByKbId(lockedKnowledgeBase.getId());
        kbDocumentOutboxRepository.deleteByKbId(lockedKnowledgeBase.getId());
        kbDocumentRepository.deleteByKbId(lockedKnowledgeBase.getId());
        lockedKnowledgeBase.setDocumentCount(0);
        lockedKnowledgeBase.setTotalChunks(0);
        lockedKnowledgeBase.setIndexingStatus(null);
        lockedKnowledgeBase.setStatus(com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus.DELETED);
        knowledgeBaseRepository.save(lockedKnowledgeBase);
        kbFileCleanupService.scheduleDrainAfterCommit(cleanupTasks.stream().map(com.josh.interviewj.knowledgebase.model.KbFileCleanupTask::getId).toList());
    }

    /**
     * Deletes one document after re-checking KB and document state inside locks.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @param docExternalId document external id
     */
    @Transactional
    public void deleteDocument(String username, UUID kbExternalId, UUID docExternalId) {
        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase(username, kbExternalId);
        KnowledgeBase lockedKnowledgeBase = knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_001, "知识库不存在"));
        validateLockedDocumentMutationKnowledgeBase(lockedKnowledgeBase);
        KbDocument document = kbDocumentRepository.findByExternalIdAndKbIdForUpdate(docExternalId, lockedKnowledgeBase.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_002, "文档不存在"));
        if (document.getStatus() == KbDocumentStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.KB_003, "知识库当前状态不允许此操作");
        }

        long chunkCount = documentChunkRepository.countByDocumentId(document.getId());
        List<com.josh.interviewj.knowledgebase.model.KbFileCleanupTask> cleanupTasks =
                kbFileCleanupService.enqueueDocumentFile(document);
        kbDocumentArtifactRepository.deleteByDocumentId(document.getId());
        documentChunkRepository.deleteByDocumentId(document.getId());
        kbDocumentOutboxRepository.deleteByDocumentId(document.getId());
        kbDocumentRepository.delete(document);
        knowledgeBaseRepository.decrementDocumentCount(lockedKnowledgeBase.getId(), 1);
        if (document.getStatus() == KbDocumentStatus.COMPLETED && chunkCount > 0) {
            knowledgeBaseRepository.decrementTotalChunks(lockedKnowledgeBase.getId(), Math.toIntExact(chunkCount));
        }
        kbFileCleanupService.scheduleDrainAfterCommit(cleanupTasks.stream().map(com.josh.interviewj.knowledgebase.model.KbFileCleanupTask::getId).toList());
    }

    /**
     * Maps a locked knowledge base entity to the public response shape.
     *
     * @param entity knowledge base entity
     * @return response payload
     */
    private KnowledgeBaseResponse toResponse(KnowledgeBase entity) {
        return KnowledgeBaseResponse.builder()
                .id(entity.getExternalId())
                .name(entity.getName())
                .description(entity.getDescription())
                .embeddingModel(entity.getEmbeddingModel())
                .vectorDimension(entity.getVectorDimension())
                .documentCount(entity.getDocumentCount())
                .totalChunks(entity.getTotalChunks())
                .version(entity.getVersion())
                .isPublic(entity.getIsPublic())
                .status(entity.getStatus())
                .indexingStatus(entity.getIndexingStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * Rejects delete operations when the locked knowledge base is already deleted.
     *
     * @param knowledgeBase locked knowledge base
     */
    private void validateLockedDeletableKnowledgeBase(KnowledgeBase knowledgeBase) {
        if (knowledgeBase.getStatus() == com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus.DELETED) {
            throw new BusinessException(ErrorCode.KB_001, "知识库不存在");
        }
    }

    /**
     * Rejects update operations when the locked knowledge base is no longer writable.
     *
     * @param knowledgeBase locked knowledge base
     */
    private void validateLockedWritableKnowledgeBase(KnowledgeBase knowledgeBase) {
        if (knowledgeBase.getStatus() == com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus.DELETED) {
            throw new BusinessException(ErrorCode.KB_001, "知识库不存在");
        }
        if (knowledgeBase.getStatus() == com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.KB_003, "知识库当前状态不允许此操作");
        }
    }

    /**
     * Rejects document mutations when the locked knowledge base has become unavailable or busy.
     *
     * @param knowledgeBase locked knowledge base
     */
    private void validateLockedDocumentMutationKnowledgeBase(KnowledgeBase knowledgeBase) {
        if (knowledgeBase.getStatus() == com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus.DELETED) {
            throw new BusinessException(ErrorCode.KB_001, "知识库不存在");
        }
        if (knowledgeBase.getStatus() == com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus.ARCHIVED
                || knowledgeBase.getIndexingStatus() == KnowledgeBaseIndexingStatus.REINDEXING) {
            throw new BusinessException(ErrorCode.KB_003, "知识库当前状态不允许此操作");
        }
    }
}
