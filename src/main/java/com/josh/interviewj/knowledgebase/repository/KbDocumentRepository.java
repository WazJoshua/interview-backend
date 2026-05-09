package com.josh.interviewj.knowledgebase.repository;

import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
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

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Accesses knowledge base document metadata and document-level status transitions.
 */
@Repository
public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

    long countByKbId(Long kbId);

    long countByKbIdAndStatus(Long kbId, KbDocumentStatus status);

    @Query("select coalesce(sum(d.fileSize), 0) from KbDocument d where d.kbId = :kbId")
    Long sumFileSizeByKbId(@Param("kbId") Long kbId);

    Optional<KbDocument> findByExternalId(UUID externalId);

    Optional<KbDocument> findByExternalIdAndKbId(UUID externalId, Long kbId);

    /**
     * Loads and locks one document under the target knowledge base for mutation-safe checks.
     *
     * @param externalId document external id
     * @param kbId knowledge base primary key
     * @return locked document when present
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from KbDocument d where d.externalId = :externalId and d.kbId = :kbId")
    Optional<KbDocument> findByExternalIdAndKbIdForUpdate(
            @Param("externalId") UUID externalId,
            @Param("kbId") Long kbId
    );

    /**
     * Loads and locks all documents under one knowledge base in stable id order.
     *
     * @param kbId knowledge base primary key
     * @return locked documents
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    java.util.List<KbDocument> findAllByKbIdOrderByIdAsc(Long kbId);

    long countByKbIdAndStatusIn(Long kbId, java.util.Collection<KbDocumentStatus> statuses);

    boolean existsByKbIdAndStatus(Long kbId, KbDocumentStatus status);

    Page<KbDocument> findByKbIdOrderByCreatedAtDesc(Long kbId, Pageable pageable);

    Page<KbDocument> findByKbIdAndStatusOrderByCreatedAtDesc(Long kbId, KbDocumentStatus status, Pageable pageable);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM kb_documents WHERE kb_id = :kbId", nativeQuery = true)
    int deleteByKbId(@Param("kbId") Long kbId);

    @Transactional
    @Modifying
    @Query("UPDATE KbDocument d SET d.status = :processingStatus, d.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE d.id = :id " +
            "AND (d.status = :pendingStatus OR (d.status = :processingStatus AND d.updatedAt < :deadline))")
    int claimForProcessing(
            @Param("id") Long id,
            @Param("deadline") LocalDateTime deadline,
            @Param("pendingStatus") KbDocumentStatus pendingStatus,
            @Param("processingStatus") KbDocumentStatus processingStatus
    );

    @Transactional
    @Modifying
    @Query("UPDATE KbDocument d SET d.expectedChunkCount = :expectedChunkCount, d.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE d.id = :id")
    int updateExpectedChunkCount(
            @Param("id") Long id,
            @Param("expectedChunkCount") Integer expectedChunkCount
    );

    @Transactional
    @Modifying
    @Query("UPDATE KbDocument d SET d.embeddedChunkCount = :embeddedChunkCount, d.chunkCount = :embeddedChunkCount, d.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE d.id = :id AND d.status = :processingStatus")
    int updateEmbeddingProgress(
            @Param("id") Long id,
            @Param("embeddedChunkCount") Integer embeddedChunkCount,
            @Param("processingStatus") KbDocumentStatus processingStatus
    );

    @Transactional
    @Modifying
    @Query("UPDATE KbDocument d SET d.status = :completedStatus, d.processedAt = :processedAt, d.errorMessage = NULL, " +
            "d.chunkCount = d.expectedChunkCount, d.embeddedChunkCount = d.expectedChunkCount, d.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE d.id = :id AND d.status = :processingStatus")
    int markCompleted(
            @Param("id") Long id,
            @Param("processingStatus") KbDocumentStatus processingStatus,
            @Param("completedStatus") KbDocumentStatus completedStatus,
            @Param("processedAt") LocalDateTime processedAt
    );

    @Transactional
    @Modifying
    @Query(
            value = """
                    UPDATE kb_documents
                    SET sparse_ready_version = :version,
                        sparse_ready_at = :readyAt,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :documentId
                    """,
            nativeQuery = true
    )
    int markSparseReady(
            @Param("documentId") Long documentId,
            @Param("version") String version,
            @Param("readyAt") LocalDateTime readyAt
    );

    @Transactional
    @Modifying
    @Query(
            value = """
                    UPDATE kb_documents
                    SET sparse_ready_version = NULL,
                        sparse_ready_at = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :documentId
                    """,
            nativeQuery = true
    )
    int clearSparseReady(@Param("documentId") Long documentId);

    /**
     * Returns true when the KB still has at least one completed document that is not sparse-ready
     * for the requested readiness version. Processing and failed documents do not block readiness.
     */
    @Query(
            value = """
                    SELECT EXISTS (
                        SELECT 1
                        FROM kb_documents
                        WHERE kb_id = :kbId
                          AND status = 'COMPLETED'
                          AND (
                              sparse_ready_version IS DISTINCT FROM :version
                              OR sparse_ready_at IS NULL
                          )
                    )
                    """,
            nativeQuery = true
    )
    boolean existsCompletedDocumentWithoutSparseReady(
            @Param("kbId") Long kbId,
            @Param("version") String version
    );

    @Transactional
    @Modifying
    @Query("UPDATE KbDocument d SET d.status = :pendingStatus, d.errorMessage = :errorMessage, d.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE d.id = :id AND d.status = :processingStatus")
    int markPendingForRetry(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage,
            @Param("pendingStatus") KbDocumentStatus pendingStatus,
            @Param("processingStatus") KbDocumentStatus processingStatus
    );

    @Transactional
    @Modifying
    @Query("UPDATE KbDocument d SET d.status = :failedStatus, d.errorMessage = :errorMessage, d.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE d.id = :id AND d.status = :processingStatus")
    int markFailed(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage,
            @Param("failedStatus") KbDocumentStatus failedStatus,
            @Param("processingStatus") KbDocumentStatus processingStatus
    );

    @Transactional
    @Modifying
    @Query("UPDATE KbDocument d SET d.updatedAt = CURRENT_TIMESTAMP WHERE d.id = :id AND d.status = :processingStatus")
    int heartbeat(
            @Param("id") Long id,
            @Param("processingStatus") KbDocumentStatus processingStatus
    );

    @Query(
            value = """
                    SELECT COUNT(*)
                    FROM document_chunks dc
                    JOIN kb_documents kd ON dc.document_id = kd.id
                    WHERE kd.kb_id = :kbId
                      AND kd.status = 'COMPLETED'
                      AND dc.embedding IS NOT NULL
                    """,
            nativeQuery = true
    )
    long countSearchableChunksByKbId(@Param("kbId") Long kbId);
}
