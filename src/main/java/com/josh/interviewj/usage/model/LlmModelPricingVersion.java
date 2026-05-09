package com.josh.interviewj.usage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "llm_model_pricing_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmModelPricingVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider", nullable = false, length = 100)
    private String provider;

    @Column(name = "model_code", nullable = false, length = 255)
    private String modelCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_family", nullable = false, length = 32)
    private UsageFamily usageFamily;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id")
    private LlmModelCatalog modelRef;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_unit", nullable = false, length = 32)
    private BillingUnit billingUnit;

    @Column(name = "prompt_token_price", precision = 18, scale = 6)
    private BigDecimal promptTokenPrice;

    @Column(name = "completion_token_price", precision = 18, scale = 6)
    private BigDecimal completionTokenPrice;

    @Column(name = "cached_token_price", precision = 18, scale = 6)
    private BigDecimal cachedTokenPrice;

    @Column(name = "request_price", precision = 18, scale = 6)
    private BigDecimal requestPrice;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

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
