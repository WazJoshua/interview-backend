package com.josh.interviewj.resume.repository;

import com.josh.interviewj.resume.outbox.ResumeParseOutbox;
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

@Repository
public interface ResumeParseOutboxRepository extends JpaRepository<ResumeParseOutbox, Long> {

    /**
     * Find outbox records by statuses ordered by creation time.
     *
     * @param statuses statuses to include
     * @return matching outbox records
     */
    List<ResumeParseOutbox> findByStatusInOrderByCreatedAtAsc(List<OutboxStatus> statuses);

    /**
     * CAS claim an outbox record for processing and set its owner.
     *
     * @param id outbox id
     * @param owner owner identifier
     * @param newStatus status to set when claimed
     * @param claimableStatuses statuses that can be claimed
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeParseOutbox o SET o.status = :newStatus, o.owner = :owner, o.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE o.id = :id AND o.status IN :claimableStatuses")
    int claimForProcessing(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("newStatus") OutboxStatus newStatus,
            @Param("claimableStatuses") List<OutboxStatus> claimableStatuses
    );

    /**
     * Mark an outbox record as SENT using owner fencing.
     *
     * @param id outbox id
     * @param owner expected owner
     * @param sentStatus status to set
     * @param sentAt sent timestamp
     * @param processingStatus expected current processing status
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeParseOutbox o SET o.status = :sentStatus, o.sentAt = :sentAt, o.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE o.id = :id AND o.status = :processingStatus AND o.owner = :owner")
    int markAsSentWithOwner(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("sentStatus") OutboxStatus sentStatus,
            @Param("sentAt") LocalDateTime sentAt,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    /**
     * Mark an outbox record as FAILED using owner fencing.
     *
     * @param id outbox id
     * @param owner expected owner
     * @param failedStatus status to set
     * @param errorMessage safe error message
     * @param processingStatus expected current processing status
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeParseOutbox o SET o.status = :failedStatus, o.errorMessage = :errorMessage, o.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE o.id = :id AND o.status = :processingStatus AND o.owner = :owner")
    int markAsFailedWithOwner(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("failedStatus") OutboxStatus failedStatus,
            @Param("errorMessage") String errorMessage,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    /**
     * Prepare an outbox record for retry, incrementing retry count and clearing owner.
     *
     * @param id outbox id
     * @param owner expected owner
     * @param retryStatus status to set
     * @param retryCount new retry count
     * @param processingStatus expected current processing status
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeParseOutbox o SET o.status = :retryStatus, o.retryCount = :retryCount, o.owner = NULL, o.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE o.id = :id AND o.status = :processingStatus AND o.owner = :owner")
    int prepareRetryWithOwner(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("retryStatus") OutboxStatus retryStatus,
            @Param("retryCount") int retryCount,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    /**
     * Recover timed-out outbox records stuck in PROCESSING by moving them back to RETRY.
     *
     * @param deadline records with updatedAt older than this are considered timed out
     * @param retryStatus status to set
     * @param processingStatus expected current processing status
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeParseOutbox o SET o.status = :retryStatus, o.owner = NULL, o.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE o.status = :processingStatus AND o.updatedAt < :deadline")
    int recoverTimedOutProcessing(
            @Param("deadline") LocalDateTime deadline,
            @Param("retryStatus") OutboxStatus retryStatus,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    /**
     * Count outbox records with statuses.
     *
     * @param statuses statuses to include
     * @return count
     */
    long countByStatusIn(List<OutboxStatus> statuses);

    Optional<ResumeParseOutbox> findByRetrySourceOutboxId(Long retrySourceOutboxId);

    boolean existsByRetrySourceOutboxId(Long retrySourceOutboxId);

    /**
     * Delete outbox records in a given status with sentAt older than threshold.
     *
     * @param status status filter
     * @param threshold sentAt threshold
     * @return number of rows deleted
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM ResumeParseOutbox o WHERE o.status = :status AND o.sentAt < :threshold")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status, @Param("threshold") LocalDateTime threshold);
}
