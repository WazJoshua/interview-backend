package com.josh.interviewj.usage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "llm_usage_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_family", nullable = false, length = 32)
    private UsageFamily usageFamily;

    @Column(name = "purpose", nullable = false, length = 100)
    private String purpose;

    @Column(name = "provider", nullable = false, length = 100)
    private String provider;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "model_code", nullable = false, length = 255)
    private String modelCode;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_external_id", nullable = false, length = 128)
    private String resourceExternalId;

    @Column(name = "operation_id", nullable = false, length = 128)
    private String operationId;

    @Column(name = "business_operation_id", length = 128)
    private String businessOperationId;

    @Column(name = "request_count", nullable = false)
    private Long requestCount;

    @Column(name = "prompt_tokens")
    private Long promptTokens;

    @Column(name = "completion_tokens")
    private Long completionTokens;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(name = "cached_tokens")
    private Long cachedTokens;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_bucket", length = 32)
    private ChargeBucket chargeBucket;

    @Column(name = "business_outcome", nullable = false, length = 64)
    private String businessOutcome;

    @Column(name = "execution_disposition", nullable = false, length = 64)
    private String executionDisposition;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "dedupe_key", nullable = false, length = 512)
    private String dedupeKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Business occurrence time - when the usage actually happened.
     * Used for time window filtering, sorting, and user-visible timeline.
     * Distinct from createdAt which only represents database row creation time.
     */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    public void prePersist() {
        if (externalId == null) {
            externalId = UUID.randomUUID();
        }
        if (requestCount == null) {
            requestCount = 0L;
        }
        if (executionDisposition == null || executionDisposition.isBlank()) {
            executionDisposition = "EXECUTED";
        }
    }
}
