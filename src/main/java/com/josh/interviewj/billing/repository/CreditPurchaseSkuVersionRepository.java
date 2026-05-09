package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.CreditPurchaseSkuVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreditPurchaseSkuVersionRepository extends JpaRepository<CreditPurchaseSkuVersion, Long> {

    @Query(
            value = """
                    SELECT *
                    FROM credit_purchase_sku_version version
                    WHERE version.credit_purchase_sku_id = :skuId
                      AND (:excludeId IS NULL OR version.id <> :excludeId)
                      AND version.effective_from < COALESCE(:effectiveTo, 'infinity'::timestamp)
                      AND COALESCE(version.effective_to, 'infinity'::timestamp) > :effectiveFrom
                    ORDER BY version.effective_from ASC
                    """,
            nativeQuery = true
    )
    List<CreditPurchaseSkuVersion> findOverlappingVersions(
            @Param("skuId") Long skuId,
            @Param("effectiveFrom") LocalDateTime effectiveFrom,
            @Param("effectiveTo") LocalDateTime effectiveTo,
            @Param("excludeId") Long excludeId
    );

    @Query(
            value = """
                    SELECT *
                    FROM credit_purchase_sku_version version
                    WHERE version.credit_purchase_sku_id = :skuId
                      AND version.sale_enabled = TRUE
                      AND version.effective_from <= :effectiveAt
                      AND (version.effective_to IS NULL OR version.effective_to > :effectiveAt)
                    ORDER BY version.effective_from DESC
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<CreditPurchaseSkuVersion> findActiveSellableVersion(
            @Param("skuId") Long skuId,
            @Param("effectiveAt") LocalDateTime effectiveAt
    );
}
