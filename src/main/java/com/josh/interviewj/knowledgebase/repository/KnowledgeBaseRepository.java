package com.josh.interviewj.knowledgebase.repository;

import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Accesses knowledge base aggregates and aggregate-level counters.
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    Optional<KnowledgeBase> findByExternalId(UUID externalId);

    Optional<KnowledgeBase> findByExternalIdAndUserId(UUID externalId, Long userId);

    Optional<KnowledgeBase> findByExternalIdAndUserIdAndStatus(UUID externalId, Long userId, KnowledgeBaseStatus status);

    Page<KnowledgeBase> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<KnowledgeBase> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, KnowledgeBaseStatus status, Pageable pageable);

    Page<KnowledgeBase> findByUserIdAndStatusInOrderByCreatedAtDesc(Long userId, java.util.Collection<KnowledgeBaseStatus> statuses, Pageable pageable);

    /**
     * Loads and locks one knowledge base row for mutation-safe lifecycle operations.
     *
     * @param id knowledge base primary key
     * @return locked knowledge base
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select kb from KnowledgeBase kb where kb.id = :id")
    Optional<KnowledgeBase> findByIdForUpdate(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBase kb SET kb.totalChunks = kb.totalChunks + :delta, kb.updatedAt = CURRENT_TIMESTAMP WHERE kb.id = :id")
    int incrementTotalChunks(@Param("id") Long id, @Param("delta") Integer delta);

    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBase kb SET kb.documentCount = CASE WHEN kb.documentCount - :delta < 0 THEN 0 ELSE kb.documentCount - :delta END, kb.updatedAt = CURRENT_TIMESTAMP WHERE kb.id = :id")
    int decrementDocumentCount(@Param("id") Long id, @Param("delta") Integer delta);

    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBase kb SET kb.totalChunks = CASE WHEN kb.totalChunks - :delta < 0 THEN 0 ELSE kb.totalChunks - :delta END, kb.updatedAt = CURRENT_TIMESTAMP WHERE kb.id = :id")
    int decrementTotalChunks(@Param("id") Long id, @Param("delta") Integer delta);

    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBase kb SET kb.totalChunks = :totalChunks, kb.documentCount = :documentCount, kb.updatedAt = CURRENT_TIMESTAMP WHERE kb.id = :id")
    int resetCounts(@Param("id") Long id, @Param("documentCount") Integer documentCount, @Param("totalChunks") Integer totalChunks);

    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBase kb SET kb.indexingStatus = :indexingStatus, kb.updatedAt = CURRENT_TIMESTAMP WHERE kb.id = :id")
    int updateIndexingStatus(@Param("id") Long id, @Param("indexingStatus") com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus indexingStatus);
}
