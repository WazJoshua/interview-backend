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

import java.time.LocalDateTime;

@Entity
@Table(name = "llm_usage_credit_ledger")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsageCreditLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usage_event_id", nullable = false, unique = true)
    private Long usageEventId;

    @Column(name = "credit_policy_version_id")
    private Long creditPolicyVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_bucket", length = 32)
    private ChargeBucket chargeBucket;

    @Column(name = "charged_credits_micros")
    private Long chargedCreditsMicros;

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
