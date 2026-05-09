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

/**
 * Master inventory record for a billing plan version.
 * Tracks total capacity and reservation/confirmation counts.
 */
@Entity
@Table(name = "billing_plan_inventory")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingPlanInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "billing_plan_version_id", nullable = false, unique = true)
    private Long billingPlanVersionId;

    /**
     * Total saleable capacity for this plan version.
     */
    @Column(name = "total_capacity", nullable = false)
    private Long totalCapacity;

    /**
     * Count of currently reserved (unconfirmed) inventory.
     */
    @Column(name = "reserved_count", nullable = false)
    private Long reservedCount;

    /**
     * Count of confirmed (sold) inventory.
     */
    @Column(name = "confirmed_count", nullable = false)
    private Long confirmedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Calculate available inventory: total - reserved - confirmed.
     */
    public Long getAvailableCount() {
        return totalCapacity - reservedCount - confirmedCount;
    }
}