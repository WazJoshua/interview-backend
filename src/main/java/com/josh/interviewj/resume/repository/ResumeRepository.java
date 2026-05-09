package com.josh.interviewj.resume.repository;

import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.model.ResumeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResumeRepository extends JpaRepository<Resume, Long>, JpaSpecificationExecutor<Resume> {

    /**
     * Find a resume by external id with ownership check and soft-delete filter.
     *
     * @param externalId resume external UUID
     * @param userId owner user id
     * @return optional resume
     */
    Optional<Resume> findByExternalIdAndUserIdAndDeletedAtIsNull(UUID externalId, Long userId);

    /**
     * Check whether a user already uploaded the same resume content (sha-256 hash) and it is not deleted.
     *
     * @param userId owner user id
     * @param contentHash sha-256 hash
     * @return true if a duplicate exists
     */
    boolean existsByUserIdAndContentHashAndDeletedAtIsNull(Long userId, String contentHash);

    /**
     * CAS claim for processing: move a resume into PARSING.
     *
     * <p>Allows takeover when the current status is already processing and the last update time is older than
     * the provided deadline (heartbeat-based fencing).</p>
     *
     * @param id resume primary key
     * @param deadline takeover deadline
     * @param pendingStatus expected pending status
     * @param processingStatus processing status to set
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE Resume r SET r.status = :processingStatus, r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.id = :id " +
            "AND (r.status = :pendingStatus OR (r.status = :processingStatus AND r.updatedAt < :deadline))")
    int claimForProcessing(
            @Param("id") Long id,
            @Param("deadline") LocalDateTime deadline,
            @Param("pendingStatus") ResumeStatus pendingStatus,
            @Param("processingStatus") ResumeStatus processingStatus
    );

    /**
     * Update status with an expected-status condition (optimistic CAS).
     *
     * @param id resume primary key
     * @param expectedStatus expected current status
     * @param newStatus new status
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE Resume r SET r.status = :newStatus, r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.id = :id AND r.status = :expectedStatus")
    int updateStatusWithCondition(
            @Param("id") Long id,
            @Param("expectedStatus") ResumeStatus expectedStatus,
            @Param("newStatus") ResumeStatus newStatus
    );

    /**
     * Marks a parsing resume as parsed and records the parse completion time in the same CAS update.
     *
     * @param id resume primary key
     * @param expectedStatus expected current status
     * @param parsedStatus parsed status to set
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE Resume r SET r.status = :parsedStatus, r.parsedAt = CURRENT_TIMESTAMP, r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.id = :id AND r.status = :expectedStatus")
    int markParsedWithTimestamp(
            @Param("id") Long id,
            @Param("expectedStatus") ResumeStatus expectedStatus,
            @Param("parsedStatus") ResumeStatus parsedStatus
    );

    /**
     * Prepare a resume for retry by moving it back to PENDING and incrementing retry count.
     *
     * @param id resume primary key
     * @param maxRetries max retry limit
     * @param pendingStatus status to set for retry
     * @param processingStatus expected current processing status
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE Resume r SET r.status = :pendingStatus, r.retryCount = r.retryCount + 1, r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.id = :id AND r.status = :processingStatus AND r.retryCount < :maxRetries")
    int prepareForRetry(
            @Param("id") Long id,
            @Param("maxRetries") int maxRetries,
            @Param("pendingStatus") ResumeStatus pendingStatus,
            @Param("processingStatus") ResumeStatus processingStatus
    );

    /**
     * Mark a resume as failed from a processing state.
     *
     * @param id resume primary key
     * @param errorMessage safe error message
     * @param failedStatus failed status to set
     * @param processingStatus expected current status
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE Resume r SET r.status = :failedStatus, r.errorMessage = :errorMessage, r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.id = :id AND r.status = :processingStatus")
    int markAsFailed(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage,
            @Param("failedStatus") ResumeStatus failedStatus,
            @Param("processingStatus") ResumeStatus processingStatus
    );

    /**
     * Mark a resume as failed from the outbox publisher side.
     *
     * <p>This is used when message publishing is repeatedly failing and the system decides the task cannot proceed.</p>
     *
     * @param id resume primary key
     * @param errorMessage safe error message
     * @param failedStatus failed status to set
     * @param allowedStatuses statuses that can be transitioned to failed
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE Resume r SET r.status = :failedStatus, r.errorMessage = :errorMessage, r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.id = :id AND r.status IN :allowedStatuses")
    int markAsFailedFromOutbox(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage,
            @Param("failedStatus") ResumeStatus failedStatus,
            @Param("allowedStatuses") List<ResumeStatus> allowedStatuses
    );

    /**
     * Increment resume retry count.
     *
     * @param id resume primary key
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE Resume r SET r.retryCount = r.retryCount + 1 WHERE r.id = :id")
    int incrementRetryCount(@Param("id") Long id);

    /**
     * Heartbeat update to keep PARSING resume active.
     *
     * @param id resume primary key
     * @param processingStatus expected processing status
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query("UPDATE Resume r SET r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id AND r.status = :processingStatus")
    int heartbeat(@Param("id") Long id, @Param("processingStatus") ResumeStatus processingStatus);
}
