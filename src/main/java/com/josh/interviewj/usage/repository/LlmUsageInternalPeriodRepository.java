package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.LlmUsageInternalPeriod;
import com.josh.interviewj.usage.model.PeriodType;
import com.josh.interviewj.usage.model.UsageFamily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LlmUsageInternalPeriodRepository extends JpaRepository<LlmUsageInternalPeriod, Long> {

    @Transactional
    @Modifying
    @Query(
            value = """
                    INSERT INTO llm_usage_internal_period (
                        period_type,
                        period_start,
                        period_end,
                        provider,
                        model_code,
                        usage_family,
                        purpose,
                        total_recorded_tokens,
                        total_recorded_cached_tokens,
                        total_request_count,
                        total_chargeable_tokens,
                        total_chargeable_request_count,
                        total_billed_amount,
                        currency
                    )
                    VALUES (
                        :periodType,
                        :periodStart,
                        :periodEnd,
                        :provider,
                        :modelCode,
                        :usageFamily,
                        :purpose,
                        :totalRecordedTokens,
                        :totalRecordedCachedTokens,
                        :totalRequestCount,
                        :totalChargeableTokens,
                        :totalChargeableRequestCount,
                        :totalBilledAmount,
                        :currency
                    )
                    ON CONFLICT (period_type, period_start, period_end, provider, model_code, usage_family, purpose)
                    DO UPDATE SET
                        total_recorded_tokens = llm_usage_internal_period.total_recorded_tokens + EXCLUDED.total_recorded_tokens,
                        total_recorded_cached_tokens = llm_usage_internal_period.total_recorded_cached_tokens + EXCLUDED.total_recorded_cached_tokens,
                        total_request_count = llm_usage_internal_period.total_request_count + EXCLUDED.total_request_count,
                        total_chargeable_tokens = llm_usage_internal_period.total_chargeable_tokens + EXCLUDED.total_chargeable_tokens,
                        total_chargeable_request_count =
                            llm_usage_internal_period.total_chargeable_request_count + EXCLUDED.total_chargeable_request_count,
                        total_billed_amount = llm_usage_internal_period.total_billed_amount + EXCLUDED.total_billed_amount,
                        updated_at = CURRENT_TIMESTAMP
                    """,
            nativeQuery = true
    )
    int upsertIncrement(
            @Param("periodType") String periodType,
            @Param("periodStart") LocalDateTime periodStart,
            @Param("periodEnd") LocalDateTime periodEnd,
            @Param("provider") String provider,
            @Param("modelCode") String modelCode,
            @Param("usageFamily") String usageFamily,
            @Param("purpose") String purpose,
            @Param("totalRecordedTokens") long totalRecordedTokens,
            @Param("totalRecordedCachedTokens") long totalRecordedCachedTokens,
            @Param("totalRequestCount") long totalRequestCount,
            @Param("totalChargeableTokens") long totalChargeableTokens,
            @Param("totalChargeableRequestCount") long totalChargeableRequestCount,
            @Param("totalBilledAmount") BigDecimal totalBilledAmount,
            @Param("currency") String currency
    );

    List<LlmUsageInternalPeriod> findByPeriodTypeAndPeriodStartAndPeriodEnd(
            PeriodType periodType,
            LocalDateTime periodStart,
            LocalDateTime periodEnd
    );
}
