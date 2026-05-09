package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.LlmModelCatalog;
import com.josh.interviewj.usage.model.UsageFamily;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LlmModelCatalogRepository extends JpaRepository<LlmModelCatalog, Long> {

    Optional<LlmModelCatalog> findByProviderAndModelCodeAndUsageFamily(String provider, String modelCode, UsageFamily usageFamily);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT catalog
            FROM LlmModelCatalog catalog
            WHERE catalog.provider = :provider
              AND catalog.modelCode = :modelCode
              AND catalog.usageFamily = :usageFamily
            """)
    Optional<LlmModelCatalog> findByProviderAndModelCodeAndUsageFamilyForUpdate(
            @Param("provider") String provider,
            @Param("modelCode") String modelCode,
            @Param("usageFamily") UsageFamily usageFamily
    );

    @Query("""
            SELECT catalog
            FROM LlmModelCatalog catalog
            LEFT JOIN FETCH catalog.providerRef
            WHERE catalog.provider = :provider
              AND catalog.modelCode = :modelCode
              AND catalog.usageFamily = :usageFamily
            """)
    Optional<LlmModelCatalog> findByProviderAndModelCodeAndUsageFamilyWithProvider(
            @Param("provider") String provider,
            @Param("modelCode") String modelCode,
            @Param("usageFamily") UsageFamily usageFamily
    );
}
