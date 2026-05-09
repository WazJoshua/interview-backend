package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.UserCreditPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserCreditPolicyRepository extends JpaRepository<UserCreditPolicy, Long> {

    @Query(
            value = """
                    SELECT *
                    FROM user_credit_policy p
                    WHERE p.user_id = :userId
                      AND p.effective_from <= :effectiveAt
                      AND (p.effective_to IS NULL OR p.effective_to > :effectiveAt)
                    ORDER BY p.effective_from DESC
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<UserCreditPolicy> findActivePolicy(@Param("userId") Long userId, @Param("effectiveAt") LocalDateTime effectiveAt);

    @Query(
            value = """
                    SELECT *
                    FROM user_credit_policy p
                    WHERE p.user_id = :userId
                      AND (:excludeId IS NULL OR p.id <> :excludeId)
                      AND p.effective_from < COALESCE(:effectiveTo, 'infinity'::timestamp)
                      AND COALESCE(p.effective_to, 'infinity'::timestamp) > :effectiveFrom
                    ORDER BY p.effective_from ASC
                    """,
            nativeQuery = true
    )
    List<UserCreditPolicy> findOverlappingPolicies(
            @Param("userId") Long userId,
            @Param("effectiveFrom") LocalDateTime effectiveFrom,
            @Param("effectiveTo") LocalDateTime effectiveTo,
            @Param("excludeId") Long excludeId
    );

    List<UserCreditPolicy> findByUserIdOrderByEffectiveFromAsc(Long userId);
}
