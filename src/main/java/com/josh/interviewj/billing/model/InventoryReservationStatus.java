package com.josh.interviewj.billing.model;

/**
 * Reservation status state machine.
 * RESERVED is the initial state, can only transition to CONFIRMED or RELEASED (terminal states).
 */
public enum InventoryReservationStatus {

    /**
     * Inventory is reserved pending payment confirmation.
     */
    RESERVED,

    /**
     * Reservation confirmed after successful payment (terminal state).
     */
    CONFIRMED,

    /**
     * Reservation released due to order expiration/cancellation/failure (terminal state).
     */
    RELEASED
}