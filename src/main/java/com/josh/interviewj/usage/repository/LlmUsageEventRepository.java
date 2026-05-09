package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.LlmUsageEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface LlmUsageEventRepository extends JpaRepository<LlmUsageEvent, Long> {

    Optional<LlmUsageEvent> findByDedupeKey(String dedupeKey);

    @Query(
            value = """
                    SELECT e.*
                    FROM llm_usage_event e
                    WHERE e.user_id = :userId
                      AND (:purpose IS NULL OR e.purpose = :purpose)
                      AND (:usageFamily IS NULL OR e.usage_family = :usageFamily)
                      AND (:chargeBucket IS NULL OR e.charge_bucket = :chargeBucket)
                      AND e.created_at >= :from
                      AND e.created_at < :to
                    ORDER BY e.created_at DESC, e.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM llm_usage_event e
                    WHERE e.user_id = :userId
                      AND (:purpose IS NULL OR e.purpose = :purpose)
                      AND (:usageFamily IS NULL OR e.usage_family = :usageFamily)
                      AND (:chargeBucket IS NULL OR e.charge_bucket = :chargeBucket)
                      AND e.created_at >= :from
                      AND e.created_at < :to
                    """,
            nativeQuery = true
    )
    Page<LlmUsageEvent> findUserEvents(
            @Param("userId") Long userId,
            @Param("purpose") String purpose,
            @Param("usageFamily") String usageFamily,
            @Param("chargeBucket") String chargeBucket,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

    @Query(
            value = """
                    SELECT e.*
                    FROM llm_usage_event e
                    WHERE (:userId IS NULL OR e.user_id = :userId)
                      AND (:purpose IS NULL OR e.purpose = :purpose)
                      AND (:usageFamily IS NULL OR e.usage_family = :usageFamily)
                      AND (:chargeBucket IS NULL OR e.charge_bucket = :chargeBucket)
                      AND e.created_at >= :from
                      AND e.created_at < :to
                    ORDER BY e.created_at DESC, e.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM llm_usage_event e
                    WHERE (:userId IS NULL OR e.user_id = :userId)
                      AND (:purpose IS NULL OR e.purpose = :purpose)
                      AND (:usageFamily IS NULL OR e.usage_family = :usageFamily)
                      AND (:chargeBucket IS NULL OR e.charge_bucket = :chargeBucket)
                      AND e.created_at >= :from
                      AND e.created_at < :to
                    """,
            nativeQuery = true
    )
    Page<LlmUsageEvent> findAdminEvents(
            @Param("userId") Long userId,
            @Param("purpose") String purpose,
            @Param("usageFamily") String usageFamily,
            @Param("chargeBucket") String chargeBucket,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );
}
