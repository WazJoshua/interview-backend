package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.UsageCreditPolicyVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UsageCreditPolicyVersionRepository extends JpaRepository<UsageCreditPolicyVersion, Long> {

    @Query(
            value = """
                    SELECT *
                    FROM usage_credit_policy_version v
                    WHERE v.purpose = :purpose
                      AND v.charge_bucket = :chargeBucket
                      AND v.usage_family = :usageFamily
                      AND v.effective_from <= :effectiveAt
                      AND (v.effective_to IS NULL OR v.effective_to > :effectiveAt)
                    ORDER BY v.effective_from DESC
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<UsageCreditPolicyVersion> findActiveVersion(
            @Param("purpose") String purpose,
            @Param("chargeBucket") String chargeBucket,
            @Param("usageFamily") String usageFamily,
            @Param("effectiveAt") LocalDateTime effectiveAt
    );

    @Query(
            value = """
                    SELECT *
                    FROM usage_credit_policy_version v
                    WHERE v.purpose = :purpose
                      AND v.charge_bucket = :chargeBucket
                      AND v.usage_family = :usageFamily
                      AND (:excludeId IS NULL OR v.id <> :excludeId)
                      AND v.effective_from < COALESCE(:effectiveTo, 'infinity'::timestamp)
                      AND COALESCE(v.effective_to, 'infinity'::timestamp) > :effectiveFrom
                    ORDER BY v.effective_from ASC
                    """,
            nativeQuery = true
    )
    List<UsageCreditPolicyVersion> findOverlappingVersions(
            @Param("purpose") String purpose,
            @Param("chargeBucket") String chargeBucket,
            @Param("usageFamily") String usageFamily,
            @Param("effectiveFrom") LocalDateTime effectiveFrom,
            @Param("effectiveTo") LocalDateTime effectiveTo,
            @Param("excludeId") Long excludeId
    );
}
