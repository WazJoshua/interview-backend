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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_order")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "order_no", nullable = false, unique = true, length = 64)
    private String orderNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 64)
    private PaymentOrderType orderType;

    @Column(name = "biz_ref_type", nullable = false, length = 64)
    private String bizRefType;

    @Column(name = "biz_ref_id", nullable = false, length = 128)
    private String bizRefId;

    @Column(name = "subscription_contract_id")
    private Long subscriptionContractId;

    @Column(name = "locked_plan_version_id")
    private Long lockedPlanVersionId;

    @Column(name = "locked_purchase_sku_version_id")
    private Long lockedPurchaseSkuVersionId;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    private PaymentOrderStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "provider_order_ref", length = 128)
    private String providerOrderRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pricing_snapshot", nullable = false, columnDefinition = "jsonb")
    private String pricingSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entitlement_snapshot", nullable = false, columnDefinition = "jsonb")
    private String entitlementSnapshot;

    @Column(name = "renewal_period_start")
    private LocalDateTime renewalPeriodStart;

    @Column(name = "renewal_period_end")
    private LocalDateTime renewalPeriodEnd;

    @Column(name = "payable_activated_at")
    private LocalDateTime payableActivatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
