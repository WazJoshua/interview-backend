package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.PeriodType;
import com.josh.interviewj.usage.model.UserCreditPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserCreditPeriodRepository extends JpaRepository<UserCreditPeriod, Long> {

    @Transactional
    @Modifying
    @Query(
            value = """
                    INSERT INTO user_credit_period (
                        user_id,
                        period_type,
                        period_start,
                        period_end,
                        resume_credits_used_micros,
                        kb_query_credits_used_micros,
                        kb_ingestion_credits_used_micros,
                        interview_credits_used_micros
                    )
                    VALUES (
                        :userId,
                        :periodType,
                        :periodStart,
                        :periodEnd,
                        :resumeCreditsUsedMicros,
                        :kbQueryCreditsUsedMicros,
                        :kbIngestionCreditsUsedMicros,
                        :interviewCreditsUsedMicros
                    )
                    ON CONFLICT (user_id, period_type, period_start, period_end)
                    DO UPDATE SET
                        resume_credits_used_micros =
                            user_credit_period.resume_credits_used_micros + EXCLUDED.resume_credits_used_micros,
                        kb_query_credits_used_micros =
                            user_credit_period.kb_query_credits_used_micros + EXCLUDED.kb_query_credits_used_micros,
                        kb_ingestion_credits_used_micros =
                            user_credit_period.kb_ingestion_credits_used_micros + EXCLUDED.kb_ingestion_credits_used_micros,
                        interview_credits_used_micros =
                            user_credit_period.interview_credits_used_micros + EXCLUDED.interview_credits_used_micros,
                        updated_at = CURRENT_TIMESTAMP
                    """,
            nativeQuery = true
    )
    int upsertIncrement(
            @Param("userId") Long userId,
            @Param("periodType") String periodType,
            @Param("periodStart") LocalDateTime periodStart,
            @Param("periodEnd") LocalDateTime periodEnd,
            @Param("resumeCreditsUsedMicros") long resumeCreditsUsedMicros,
            @Param("kbQueryCreditsUsedMicros") long kbQueryCreditsUsedMicros,
            @Param("kbIngestionCreditsUsedMicros") long kbIngestionCreditsUsedMicros,
            @Param("interviewCreditsUsedMicros") long interviewCreditsUsedMicros
    );

    Optional<UserCreditPeriod> findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(
            Long userId,
            PeriodType periodType,
            LocalDateTime periodStart,
            LocalDateTime periodEnd
    );
}
