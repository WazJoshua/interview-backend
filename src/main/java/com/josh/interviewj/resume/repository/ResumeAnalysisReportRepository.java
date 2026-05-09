package com.josh.interviewj.resume.repository;

import com.josh.interviewj.resume.model.ResumeAnalysisReport;
import com.josh.interviewj.resume.model.AnalysisStatus;
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
 * Repository for resume analysis report lifecycle and status updates.
 */
@Repository
public interface ResumeAnalysisReportRepository extends JpaRepository<ResumeAnalysisReport, Long> {

    /**
     * Finds a report by resume id and owner id.
     *
     * @param resumeId internal resume id
     * @param userId internal user id
     * @return optional analysis report
     */
    Optional<ResumeAnalysisReport> findByResumeIdAndUserId(Long resumeId, Long userId);

    /**
     * Finds a report by internal resume id.
     *
     * @param resumeId internal resume id
     * @return optional analysis report
     */
    Optional<ResumeAnalysisReport> findByResumeId(Long resumeId);

    /**
     * Updates report status only when current status differs from target status.
     *
     * @param id report id
     * @param status target status
     * @return number of updated rows
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeAnalysisReport r SET r.status = :status, r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.id = :id AND r.status <> :status")
    int updateStatus(@Param("id") Long id, @Param("status") AnalysisStatus status);

    /**
     * Claims a report for analysis, allowing timeout-based takeover of a stale ANALYZING row.
     *
     * @param id               report id
     * @param deadline         stale processing deadline
     * @param pendingStatus    pending status
     * @param processingStatus processing status
     * @return updated row count
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeAnalysisReport r SET r.status = :processingStatus, r.updatedAt = CURRENT_TIMESTAMP, r.errorMessage = NULL " +
            "WHERE r.id = :id " +
            "AND (r.status = :pendingStatus OR (r.status = :processingStatus AND r.updatedAt < :deadline))")
    int claimForAnalysis(
            @Param("id") Long id,
            @Param("deadline") LocalDateTime deadline,
            @Param("pendingStatus") AnalysisStatus pendingStatus,
            @Param("processingStatus") AnalysisStatus processingStatus
    );

    /**
     * Moves a claimed report back to pending state for another business retry.
     *
     * @param id               report id
     * @param errorMessage     safe retry reason
     * @param pendingStatus    pending status
     * @param processingStatus expected current status
     * @return updated row count
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeAnalysisReport r SET r.status = :pendingStatus, r.errorMessage = :errorMessage, " +
            "r.retryCount = r.retryCount + 1, r.completedAt = NULL, r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.id = :id AND r.status = :processingStatus")
    int markPendingForRetry(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage,
            @Param("pendingStatus") AnalysisStatus pendingStatus,
            @Param("processingStatus") AnalysisStatus processingStatus
    );

    /**
     * Marks a report as failed from a processing or pending state.
     *
     * @param id              report id
     * @param errorMessage    safe failure message
     * @param failedStatus    failed status
     * @param allowedStatuses allowed current statuses
     * @return updated row count
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeAnalysisReport r SET r.status = :failedStatus, r.errorMessage = :errorMessage, " +
            "r.completedAt = CURRENT_TIMESTAMP, r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.id = :id AND r.status IN :allowedStatuses")
    int markFailed(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage,
            @Param("failedStatus") AnalysisStatus failedStatus,
            @Param("allowedStatuses") List<AnalysisStatus> allowedStatuses
    );

    /**
     * Updates the report heartbeat while it is actively analyzing.
     *
     * @param id               report id
     * @param processingStatus expected status
     * @return updated row count
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeAnalysisReport r SET r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id AND r.status = :processingStatus")
    int heartbeat(@Param("id") Long id, @Param("processingStatus") AnalysisStatus processingStatus);
}
