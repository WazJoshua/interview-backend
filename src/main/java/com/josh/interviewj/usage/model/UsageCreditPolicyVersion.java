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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "usage_credit_policy_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageCreditPolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purpose", nullable = false, length = 100)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_bucket", nullable = false, length = 32)
    private ChargeBucket chargeBucket;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_family", nullable = false, length = 32)
    private UsageFamily usageFamily;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_unit", nullable = false, length = 32)
    private BillingUnit billingUnit;

    @Column(name = "prompt_token_ratio", precision = 18, scale = 6)
    private BigDecimal promptTokenRatio;

    @Column(name = "completion_token_ratio", precision = 18, scale = 6)
    private BigDecimal completionTokenRatio;

    @Column(name = "cached_token_ratio", precision = 18, scale = 6)
    private BigDecimal cachedTokenRatio;

    @Column(name = "request_ratio", precision = 18, scale = 6)
    private BigDecimal requestRatio;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
