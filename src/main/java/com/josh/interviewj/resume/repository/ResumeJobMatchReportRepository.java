package com.josh.interviewj.resume.repository;

import com.josh.interviewj.resume.model.ResumeJobMatchReport;
import com.josh.interviewj.resume.model.AnalysisStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Repository for resume × JD match report lifecycle.
 */
@Repository
public interface ResumeJobMatchReportRepository extends JpaRepository<ResumeJobMatchReport, Long> {

    Optional<ResumeJobMatchReport> findByIdAndUserId(Long id, Long userId);

    Optional<ResumeJobMatchReport> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    Page<ResumeJobMatchReport> findByResumeIdAndUserIdAndDeletedAtIsNull(Long resumeId, Long userId, Pageable pageable);

    @Transactional
    @Modifying
    @Query("UPDATE ResumeJobMatchReport r SET r.status = :status, r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.id = :id AND r.status <> :status AND r.deletedAt IS NULL")
    int updateStatus(@Param("id") Long id, @Param("status") AnalysisStatus status);

    @Transactional
    @Modifying
    @Query("UPDATE ResumeJobMatchReport r SET r.deletedAt = CURRENT_TIMESTAMP, r.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE r.id = :id AND r.userId = :userId AND r.deletedAt IS NULL")
    int softDeleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}

