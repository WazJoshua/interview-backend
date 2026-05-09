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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "llm_usage_charge_ledger")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsageChargeLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usage_event_id", nullable = false, unique = true)
    private Long usageEventId;

    @Column(name = "pricing_version_id")
    private Long pricingVersionId;

    @Column(name = "prompt_token_units", nullable = false)
    private Long promptTokenUnits;

    @Column(name = "completion_token_units", nullable = false)
    private Long completionTokenUnits;

    @Column(name = "cached_token_units", nullable = false)
    private Long cachedTokenUnits;

    @Column(name = "request_units", nullable = false)
    private Long requestUnits;

    @Column(name = "prompt_amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal promptAmount;

    @Column(name = "completion_amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal completionAmount;

    @Column(name = "cached_amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal cachedAmount;

    @Column(name = "request_amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal requestAmount;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 16)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_status", nullable = false, length = 32)
    private ChargeStatus chargeStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
