package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.LlmModelPricingVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LlmModelPricingVersionRepository extends JpaRepository<LlmModelPricingVersion, Long> {

    @Query("""
            SELECT version
            FROM LlmModelPricingVersion version
            LEFT JOIN FETCH version.modelRef model
            LEFT JOIN FETCH model.providerRef
            """)
    List<LlmModelPricingVersion> findAllWithModelAndProvider();

    @Query(
            value = """
                    SELECT *
                    FROM llm_model_pricing_version v
                    WHERE v.model_id = :modelId
                      AND v.effective_from <= :effectiveAt
                      AND (v.effective_to IS NULL OR v.effective_to > :effectiveAt)
                    ORDER BY v.effective_from DESC
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<LlmModelPricingVersion> findActiveVersionByModelId(
            @Param("modelId") Long modelId,
            @Param("effectiveAt") LocalDateTime effectiveAt
    );

    @Query(
            value = """
                    SELECT *
                    FROM llm_model_pricing_version v
                    WHERE v.provider = :provider
                      AND v.model_code = :modelCode
                      AND v.usage_family = :usageFamily
                      AND v.effective_from <= :effectiveAt
                      AND (v.effective_to IS NULL OR v.effective_to > :effectiveAt)
                    ORDER BY v.effective_from DESC
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<LlmModelPricingVersion> findActiveVersion(
            @Param("provider") String provider,
            @Param("modelCode") String modelCode,
            @Param("usageFamily") String usageFamily,
            @Param("effectiveAt") LocalDateTime effectiveAt
    );

    @Query(
            value = """
                    SELECT *
                    FROM llm_model_pricing_version v
                    WHERE v.provider = :provider
                      AND v.model_code = :modelCode
                      AND v.usage_family = :usageFamily
                      AND (:excludeId IS NULL OR v.id <> :excludeId)
                      AND v.effective_from < COALESCE(:effectiveTo, 'infinity'::timestamp)
                      AND COALESCE(v.effective_to, 'infinity'::timestamp) > :effectiveFrom
                    ORDER BY v.effective_from ASC
                    """,
            nativeQuery = true
    )
    List<LlmModelPricingVersion> findOverlappingVersions(
            @Param("provider") String provider,
            @Param("modelCode") String modelCode,
            @Param("usageFamily") String usageFamily,
            @Param("effectiveFrom") LocalDateTime effectiveFrom,
            @Param("effectiveTo") LocalDateTime effectiveTo,
            @Param("excludeId") Long excludeId
    );

    @Query(
            value = """
                    SELECT *
                    FROM llm_model_pricing_version v
                    WHERE v.model_id = :modelId
                      AND (:excludeId IS NULL OR v.id <> :excludeId)
                      AND v.effective_from < COALESCE(:effectiveTo, 'infinity'::timestamp)
                      AND COALESCE(v.effective_to, 'infinity'::timestamp) > :effectiveFrom
                    ORDER BY v.effective_from ASC
                    """,
            nativeQuery = true
    )
    List<LlmModelPricingVersion> findOverlappingVersionsByModelId(
            @Param("modelId") Long modelId,
            @Param("effectiveFrom") LocalDateTime effectiveFrom,
            @Param("effectiveTo") LocalDateTime effectiveTo,
            @Param("excludeId") Long excludeId
    );
}
