package com.josh.interviewj.user.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface UserResumeOverviewReadRepository extends Repository<com.josh.interviewj.resume.model.Resume, Long> {

    @Query(
            value = """
                    SELECT AVG(report.overall_score)::numeric
                    FROM resume_analysis_reports report
                    JOIN resumes resume ON resume.id = report.resume_id
                    WHERE resume.user_id = :targetUserId
                      AND resume.deleted_at IS NULL
                      AND report.status = 'COMPLETED'
                    """,
            nativeQuery = true
    )
    BigDecimal findAverageScoreByUserId(@Param("targetUserId") Long targetUserId);

    @Query(
            value = """
                    SELECT resume.external_id AS resumeId,
                           resume.file_name AS fileName,
                           resume.status AS uploadStatus,
                           resume.created_at AS uploadedAt,
                           resume.parsed_at AS parsedAt,
                           resume.analysis_status AS analysisStatus,
                           report.completed_at AS analysisAt
                    FROM resumes resume
                    LEFT JOIN resume_analysis_reports report ON report.resume_id = resume.id
                    WHERE resume.user_id = :targetUserId
                      AND resume.deleted_at IS NULL
                    ORDER BY resume.created_at DESC
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<LatestResumeProjection> findLatestResumeActivity(@Param("targetUserId") Long targetUserId);

    interface LatestResumeProjection {
        UUID getResumeId();

        String getFileName();

        String getUploadStatus();

        LocalDateTime getUploadedAt();

        LocalDateTime getParsedAt();

        String getAnalysisStatus();

        LocalDateTime getAnalysisAt();
    }
}
