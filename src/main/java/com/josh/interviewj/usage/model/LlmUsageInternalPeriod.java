package com.josh.interviewj.usage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "llm_usage_internal_period")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsageInternalPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 20)
    private PeriodType periodType;

    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    @Column(name = "provider", nullable = false, length = 100)
    private String provider;

    @Column(name = "model_code", nullable = false, length = 255)
    private String modelCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_family", nullable = false, length = 32)
    private UsageFamily usageFamily;

    @Column(name = "purpose", nullable = false, length = 100)
    private String purpose;

    @Column(name = "total_recorded_tokens", nullable = false)
    private Long totalRecordedTokens;

    @Column(name = "total_recorded_cached_tokens", nullable = false)
    private Long totalRecordedCachedTokens;

    @Column(name = "total_request_count", nullable = false)
    private Long totalRequestCount;

    @Column(name = "total_chargeable_tokens", nullable = false)
    private Long totalChargeableTokens;

    @Column(name = "total_chargeable_request_count", nullable = false)
    private Long totalChargeableRequestCount;

    @Column(name = "total_billed_amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal totalBilledAmount;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
