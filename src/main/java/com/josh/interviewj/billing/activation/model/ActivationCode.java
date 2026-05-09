package com.josh.interviewj.billing.activation.model;

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

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "activation_code")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 14)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "code_type", nullable = false, length = 20)
    private ActivationCodeType codeType;

    @Column(name = "billing_plan_version_id")
    private Long billingPlanVersionId;

    @Column(name = "subscription_duration_days")
    private Integer subscriptionDurationDays;

    @Column(name = "credit_amount_micros")
    private Long creditAmountMicros;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private ActivationCodeStatus status = ActivationCodeStatus.UNUSED;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "redeemed_by_user_id")
    private Long redeemedByUserId;

    @Column(name = "redeemed_at")
    private LocalDateTime redeemedAt;

    @Column(name = "billing_event_id")
    private Long billingEventId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "note", length = 500)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
