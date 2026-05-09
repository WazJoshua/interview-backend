package com.josh.interviewj.usage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "usage_rejection_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageRejectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "dedupe_key", nullable = false, unique = true, length = 255)
    private String dedupeKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "charge_bucket", nullable = false, length = 32)
    private String chargeBucket;

    @Column(name = "usage_family", nullable = false, length = 32)
    private String usageFamily;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_external_id", nullable = false, length = 128)
    private String resourceExternalId;

    @Column(name = "operation_id", nullable = false, length = 128)
    private String operationId;

    @Column(name = "business_operation_id", length = 128)
    private String businessOperationId;

    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;

    @Column(name = "reason_message", nullable = false, length = 255)
    private String reasonMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (externalId == null) {
            externalId = UUID.randomUUID();
        }
    }
}
