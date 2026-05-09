package com.josh.interviewj.billing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscription_quota_grant")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionQuotaGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "subscription_contract_id", nullable = false)
    private Long subscriptionContractId;

    @Column(name = "source_billing_event_id", nullable = false)
    private Long sourceBillingEventId;

    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    @Column(name = "bucket_code", nullable = false, length = 32)
    private String bucketCode;

    @Column(name = "granted_amount_micros", nullable = false)
    private Long grantedAmountMicros;

    @Column(name = "used_amount_micros", nullable = false)
    private Long usedAmountMicros;

    @Column(name = "expired_amount_micros", nullable = false)
    private Long expiredAmountMicros;

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
