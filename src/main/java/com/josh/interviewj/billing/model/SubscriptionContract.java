package com.josh.interviewj.billing.model;

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

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscription_contract")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "billing_plan_id", nullable = false)
    private Long billingPlanId;

    @Column(name = "billing_plan_version_id", nullable = false)
    private Long billingPlanVersionId;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "provider_subscription_ref", unique = true, length = 128)
    private String providerSubscriptionRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    private SubscriptionContractStatus status;

    @Column(name = "current_period_start")
    private LocalDateTime currentPeriodStart;

    @Column(name = "current_period_end")
    private LocalDateTime currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    @Builder.Default
    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew = true;

    @Column(name = "next_plan_version_id")
    private Long nextPlanVersionId;

    @Column(name = "grace_until")
    private LocalDateTime graceUntil;

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
