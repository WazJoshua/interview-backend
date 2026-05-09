package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.LlmUsageCreditLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface LlmUsageCreditLedgerRepository extends JpaRepository<LlmUsageCreditLedger, Long> {

    Optional<LlmUsageCreditLedger> findByUsageEventId(Long usageEventId);

    @Query(
            value = """
                    SELECT l.*
                    FROM llm_usage_credit_ledger l
                    JOIN llm_usage_event e ON e.id = l.usage_event_id
                    WHERE e.user_id = :userId
                      AND e.created_at >= :from
                      AND e.created_at < :to
                    ORDER BY e.created_at DESC, e.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM llm_usage_credit_ledger l
                    JOIN llm_usage_event e ON e.id = l.usage_event_id
                    WHERE e.user_id = :userId
                      AND e.created_at >= :from
                      AND e.created_at < :to
                    """,
            nativeQuery = true
    )
    Page<LlmUsageCreditLedger> findUserLedgerPage(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

    @Query(
            value = """
                    SELECT COALESCE(SUM(COALESCE((l.metadata ->> 'purchasedAllocatedMicros')::bigint, 0)), 0)
                    FROM llm_usage_credit_ledger l
                    JOIN llm_usage_event e ON e.id = l.usage_event_id
                    WHERE e.user_id = :userId
                      AND l.charge_status = 'CHARGEABLE'
                      AND COALESCE(l.charged_credits_micros, 0) > 0
                    """,
            nativeQuery = true
    )
    Long sumPurchasedAllocatedCreditsMicros(@Param("userId") Long userId);
}
