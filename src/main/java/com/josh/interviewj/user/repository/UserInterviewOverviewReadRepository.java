package com.josh.interviewj.user.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface UserInterviewOverviewReadRepository extends Repository<com.josh.interviewj.interview.model.InterviewSession, Long> {

    @Query(
            value = """
                    SELECT AVG(report.overall_score)
                    FROM interview_reports report
                    JOIN interview_sessions session ON session.id = report.session_id
                    WHERE session.user_id = :targetUserId
                      AND session.deleted_at IS NULL
                      AND session.status = 'COMPLETED'
                      AND report.status = 'READY'
                      AND report.overall_score IS NOT NULL
                    """,
            nativeQuery = true
    )
    BigDecimal findAverageScoreByUserId(@Param("targetUserId") Long targetUserId);

    @Query(
            value = """
                    SELECT COUNT(*)
                    FROM interview_sessions session
                    WHERE session.user_id = :targetUserId
                      AND session.deleted_at IS NULL
                      AND session.status = 'COMPLETED'
                    """,
            nativeQuery = true
    )
    long countCompletedMockInterviewsByUserId(@Param("targetUserId") Long targetUserId);

    @Query(
            value = """
                    SELECT session.external_id AS interviewId,
                           session.status AS status,
                           report.status AS reportStatus,
                           CASE
                               WHEN report.status = 'READY' THEN report.overall_score
                               ELSE NULL
                           END AS score,
                           COALESCE(
                               chat.last_message_at,
                               session.end_time,
                               session.start_time,
                               session.updated_at,
                               session.created_at
                           ) AS occurredAt
                    FROM interview_sessions session
                    LEFT JOIN interview_reports report ON report.session_id = session.id
                    LEFT JOIN chat_sessions chat ON chat.external_id = session.chat_session_id
                    WHERE session.user_id = :targetUserId
                      AND session.deleted_at IS NULL
                    ORDER BY COALESCE(
                        chat.last_message_at,
                        session.end_time,
                        session.start_time,
                        session.updated_at,
                        session.created_at
                    ) DESC
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<LatestInterviewProjection> findLatestInterviewActivity(@Param("targetUserId") Long targetUserId);

    interface LatestInterviewProjection {
        UUID getInterviewId();

        String getStatus();

        String getReportStatus();

        BigDecimal getScore();

        LocalDateTime getOccurredAt();
    }
}
