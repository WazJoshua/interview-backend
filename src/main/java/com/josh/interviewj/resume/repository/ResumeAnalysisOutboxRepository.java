package com.josh.interviewj.resume.repository;

import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.resume.outbox.ResumeAnalysisOutbox;
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
 * Repository for reliable resume analysis outbox delivery.
 */
@Repository
public interface ResumeAnalysisOutboxRepository extends JpaRepository<ResumeAnalysisOutbox, Long> {

    /**
     * Finds analysis outbox rows by status ordered by creation time.
     *
     * @param statuses outbox statuses to include
     * @return matching rows
     */
    List<ResumeAnalysisOutbox> findByStatusInOrderByCreatedAtAsc(List<OutboxStatus> statuses);

    /**
     * Claims an outbox row for publishing with owner fencing.
     *
     * @param id                outbox id
     * @param owner             owner identifier
     * @param newStatus         status to set when claimed
     * @param claimableStatuses statuses that can be claimed
     * @return number of updated rows
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeAnalysisOutbox o SET o.status = :newStatus, o.owner = :owner, o.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE o.id = :id AND o.status IN :claimableStatuses")
    int claimForProcessing(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("newStatus") OutboxStatus newStatus,
            @Param("claimableStatuses") List<OutboxStatus> claimableStatuses
    );

    /**
     * Marks an outbox row as sent if the expected owner still holds the claim.
     *
     * @param id               outbox id
     * @param owner            expected owner
     * @param sentStatus       sent status
     * @param sentAt           sent timestamp
     * @param processingStatus expected current status
     * @return number of updated rows
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeAnalysisOutbox o SET o.status = :sentStatus, o.sentAt = :sentAt, o.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE o.id = :id AND o.status = :processingStatus AND o.owner = :owner")
    int markAsSentWithOwner(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("sentStatus") OutboxStatus sentStatus,
            @Param("sentAt") LocalDateTime sentAt,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    /**
     * Marks an outbox row as failed if the expected owner still holds the claim.
     *
     * @param id               outbox id
     * @param owner            expected owner
     * @param failedStatus     failed status
     * @param errorMessage     safe error message
     * @param processingStatus expected current status
     * @return number of updated rows
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeAnalysisOutbox o SET o.status = :failedStatus, o.errorMessage = :errorMessage, o.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE o.id = :id AND o.status = :processingStatus AND o.owner = :owner")
    int markAsFailedWithOwner(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("failedStatus") OutboxStatus failedStatus,
            @Param("errorMessage") String errorMessage,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    /**
     * Moves an outbox row back to retry state and clears owner fencing.
     *
     * @param id               outbox id
     * @param owner            expected owner
     * @param retryStatus      retry status
     * @param retryCount       next retry count
     * @param processingStatus expected current status
     * @return number of updated rows
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeAnalysisOutbox o SET o.status = :retryStatus, o.retryCount = :retryCount, o.owner = NULL, o.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE o.id = :id AND o.status = :processingStatus AND o.owner = :owner")
    int prepareRetryWithOwner(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("retryStatus") OutboxStatus retryStatus,
            @Param("retryCount") int retryCount,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    /**
     * Recovers outbox rows that have been stuck in processing beyond the deadline.
     *
     * @param deadline         timeout deadline
     * @param retryStatus      retry status
     * @param processingStatus expected current status
     * @return number of recovered rows
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeAnalysisOutbox o SET o.status = :retryStatus, o.owner = NULL, o.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE o.status = :processingStatus AND o.updatedAt < :deadline")
    int recoverTimedOutProcessing(
            @Param("deadline") LocalDateTime deadline,
            @Param("retryStatus") OutboxStatus retryStatus,
            @Param("processingStatus") OutboxStatus processingStatus
    );

    /**
     * Counts analysis outbox rows by status.
     *
     * @param statuses statuses to include
     * @return number of rows
     */
    long countByStatusIn(List<OutboxStatus> statuses);

    Optional<ResumeAnalysisOutbox> findByRetrySourceOutboxId(Long retrySourceOutboxId);

    boolean existsByRetrySourceOutboxId(Long retrySourceOutboxId);

    /**
     * Deletes sent rows older than the given threshold.
     *
     * @param status    target status
     * @param threshold sent timestamp threshold
     * @return number of deleted rows
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM ResumeAnalysisOutbox o WHERE o.status = :status AND o.sentAt < :threshold")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status, @Param("threshold") LocalDateTime threshold);
}
