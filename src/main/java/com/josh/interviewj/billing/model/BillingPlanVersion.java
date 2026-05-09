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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "billing_plan_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingPlanVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "billing_plan_id", nullable = false)
    private Long billingPlanId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "billing_cycle", nullable = false, length = 32)
    private String billingCycle;

    @Column(name = "amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Column(name = "sale_enabled", nullable = false)
    private boolean saleEnabled;

    @Column(name = "renewal_enabled", nullable = false)
    private boolean renewalEnabled;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    /**
     * UTC timestamp when inventory control becomes effective.
     * Orders created before this time are legacy-compatible (no reservation required).
     */
    @Column(name = "inventory_control_enabled_at")
    private LocalDateTime inventoryControlEnabledAt;

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
