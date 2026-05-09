package com.josh.interviewj.knowledgebase.repository;

import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.common.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Accesses KB document outbox rows used for asynchronous ingestion dispatch.
 */
@Repository
public interface KbDocumentOutboxRepository extends JpaRepository<KbDocumentOutbox, Long> {

    List<KbDocumentOutbox> findByStatusInOrderByCreatedAtAsc(List<OutboxStatus> statuses);

    long countByStatusIn(List<OutboxStatus> statuses);

    @Transactional
    @Modifying
    @Query(
            "UPDATE KbDocumentOutbox o SET o.status = :newStatus, o.owner = :owner, o.updatedAt = CURRENT_TIMESTAMP " +
                    "WHERE o.id = :id AND o.status IN :claimableStatuses"
    )
    int claimForProcessing(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("newStatus") OutboxStatus newStatus,
            @Param("claimableStatuses") List<OutboxStatus> claimableStatuses
    );

    @Transactional
    @Modifying
    @Query(
            "UPDATE KbDocumentOutbox o SET o.status = :sentStatus, o.sentAt = :sentAt, o.updatedAt = CURRENT_TIMESTAMP " +
                    "WHERE o.id = :id AND o.status = :processingStatus AND o.owner = :owner"
    )
    int markAsSentWithOwner(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("sentStatus") OutboxStatus sentStatus,
            @Param("sentAt") LocalDateTime sentAt,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    @Transactional
    @Modifying
    @Query(
            "UPDATE KbDocumentOutbox o SET o.status = :failedStatus, o.errorMessage = :errorMessage, o.updatedAt = CURRENT_TIMESTAMP " +
                    "WHERE o.id = :id AND o.status = :processingStatus AND o.owner = :owner"
    )
    int markAsFailedWithOwner(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("failedStatus") OutboxStatus failedStatus,
            @Param("errorMessage") String errorMessage,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    @Transactional
    @Modifying
    @Query(
            "UPDATE KbDocumentOutbox o SET o.status = :retryStatus, o.retryCount = :retryCount, o.owner = NULL, o.updatedAt = CURRENT_TIMESTAMP " +
                    "WHERE o.id = :id AND o.status = :processingStatus AND o.owner = :owner"
    )
    int prepareRetryWithOwner(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("retryStatus") OutboxStatus retryStatus,
            @Param("retryCount") int retryCount,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    @Transactional
    @Modifying
    @Query(
            "UPDATE KbDocumentOutbox o SET o.status = :retryStatus, o.owner = NULL, o.updatedAt = CURRENT_TIMESTAMP " +
                    "WHERE o.status = :processingStatus AND o.updatedAt < :deadline"
    )
    int recoverTimedOutProcessing(
            @Param("deadline") LocalDateTime deadline,
            @Param("retryStatus") OutboxStatus retryStatus,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    long countByDocumentId(Long documentId);

    long countByDocumentIdAndStatusIn(Long documentId, List<OutboxStatus> statuses);

    Optional<KbDocumentOutbox> findByRetrySourceOutboxId(Long retrySourceOutboxId);

    boolean existsByRetrySourceOutboxId(Long retrySourceOutboxId);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM kb_document_outbox WHERE document_id = :documentId", nativeQuery = true)
    int deleteByDocumentId(@Param("documentId") Long documentId);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM kb_document_outbox WHERE kb_id = :kbId", nativeQuery = true)
    int deleteByKbId(@Param("kbId") Long kbId);
}
