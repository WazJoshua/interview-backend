package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Shared access checks for knowledge-base scoped features.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseAccessService {

    private final UserRepository userRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbDocumentRepository kbDocumentRepository;

    /**
     * Resolves a username into the corresponding user entity.
     *
     * @param username login name
     * @return user entity
     */
    public User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    /**
     * Resolves a knowledge base for read-only operations.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @return readable knowledge base
     */
    public KnowledgeBase requireReadableKnowledgeBase(String username, UUID kbExternalId) {
        return requireKnowledgeBaseByMode(username, kbExternalId, AccessMode.READABLE);
    }

    /**
     * Resolves a knowledge base for mutable metadata updates.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @return writable knowledge base
     */
    public KnowledgeBase requireWritableKnowledgeBase(String username, UUID kbExternalId) {
        return requireKnowledgeBaseByMode(username, kbExternalId, AccessMode.WRITABLE);
    }

    /**
     * Resolves a knowledge base for delete operations.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @return deletable knowledge base
     */
    public KnowledgeBase requireDeletableKnowledgeBase(String username, UUID kbExternalId) {
        return requireKnowledgeBaseByMode(username, kbExternalId, AccessMode.DELETABLE);
    }

    /**
     * Resolves a knowledge base for query execution.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @return queryable knowledge base
     */
    public KnowledgeBase requireQueryableKnowledgeBase(String username, UUID kbExternalId) {
        return requireKnowledgeBaseByMode(username, kbExternalId, AccessMode.QUERYABLE);
    }

    /**
     * Resolves a knowledge base for document upload and deletion operations.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @return document-mutable knowledge base
     */
    public KnowledgeBase requireDocumentMutationKnowledgeBase(String username, UUID kbExternalId) {
        return requireKnowledgeBaseByMode(username, kbExternalId, AccessMode.DOCUMENT_MUTATION);
    }

    /**
     * Resolves a knowledge base for reindex execution.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @return reindexable knowledge base
     */
    public KnowledgeBase requireReindexableKnowledgeBase(String username, UUID kbExternalId) {
        return requireKnowledgeBaseByMode(username, kbExternalId, AccessMode.REINDEXABLE);
    }

    public KnowledgeBase requireAccessibleKnowledgeBase(String username, UUID kbExternalId) {
        return requireReadableKnowledgeBase(username, kbExternalId);
    }

    /**
     * Resolves and validates the target knowledge base, returning only its internal id.
     *
     * @param username login name
     * @param kbExternalId knowledge base external id
     * @return internal knowledge base id
     */
    public Long requireAccessibleKnowledgeBaseId(String username, UUID kbExternalId) {
        return requireReadableKnowledgeBase(username, kbExternalId).getId();
    }

    /**
     * Ensures the target document belongs to an accessible knowledge base and resolves it by external UUID.
     *
     * @param username login name
     * @param kbExternalId knowledge base external id
     * @param docExternalId document external id
     * @return accessible document entity
     */
    public KbDocument requireReadableDocument(String username, UUID kbExternalId, UUID docExternalId) {
        KnowledgeBase knowledgeBase = requireReadableKnowledgeBase(username, kbExternalId);
        return kbDocumentRepository.findByExternalIdAndKbId(docExternalId, knowledgeBase.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_002, "文档不存在"));
    }

    /**
     * Keeps the legacy readable-document entrypoint stable for existing callers.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @param docExternalId document external id
     * @return readable document
     */
    public KbDocument requireAccessibleDocument(String username, UUID kbExternalId, UUID docExternalId) {
        return requireReadableDocument(username, kbExternalId, docExternalId);
    }

    /**
     * Resolves one knowledge base and maps its current status to the correct API error semantics.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @param accessMode requested access mode
     * @return resolved knowledge base
     */
    private KnowledgeBase requireKnowledgeBaseByMode(String username, UUID kbExternalId, AccessMode accessMode) {
        User user = requireUser(username);
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByExternalId(kbExternalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_001, "知识库不存在"));

        if (knowledgeBase.getStatus() == com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus.DELETED) {
            throw new BusinessException(ErrorCode.KB_001, "知识库不存在");
        }

        if (!knowledgeBase.getUserId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.AUTH_006, "无权访问该知识库");
        }

        if (knowledgeBase.getStatus() == com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus.ARCHIVED
                && accessMode.blocksArchived()) {
            throw new BusinessException(ErrorCode.KB_003, "知识库当前状态不允许此操作");
        }
        return knowledgeBase;
    }

    /**
     * Enumerates access modes that decide whether archived knowledge bases are allowed.
     */
    private enum AccessMode {
        READABLE(false),
        WRITABLE(true),
        DELETABLE(false),
        QUERYABLE(true),
        DOCUMENT_MUTATION(true),
        REINDEXABLE(true);

        private final boolean blocksArchived;

        AccessMode(boolean blocksArchived) {
            this.blocksArchived = blocksArchived;
        }

        public boolean blocksArchived() {
            return blocksArchived;
        }
    }
}
