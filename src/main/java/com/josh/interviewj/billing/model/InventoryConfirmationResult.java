package com.josh.interviewj.billing.model;

/**
 * Result of inventory reservation confirmation.
 * Used to signal whether fulfillment should proceed or require reconciliation.
 */
public enum InventoryConfirmationResult {
    /**
     * Reservation was successfully confirmed.
     * Fulfillment should proceed normally.
     */
    CONFIRMED,

    /**
     * Legacy order (created before inventory_control_enabled_at) without reservation.
     * Fulfillment should proceed without inventory operations.
     */
    LEGACY_ALLOWED,

    /**
     * Post-cutover order (created after inventory_control_enabled_at) without reservation.
     * Order should be marked for reconciliation and fulfillment should NOT proceed.
     */
    REQUIRES_RECONCILIATION
}