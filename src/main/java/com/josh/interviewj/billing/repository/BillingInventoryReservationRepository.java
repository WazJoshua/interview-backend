package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.BillingInventoryReservation;
import com.josh.interviewj.billing.model.InventoryReservationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BillingInventoryReservationRepository extends JpaRepository<BillingInventoryReservation, Long> {

    /**
     * Find active (RESERVED) reservation by payment order ID.
     */
    Optional<BillingInventoryReservation> findByPaymentOrderIdAndStatus(
            Long paymentOrderId,
            InventoryReservationStatus status
    );

    /**
     * Find any reservation by payment order ID (regardless of status).
     */
    Optional<BillingInventoryReservation> findByPaymentOrderId(Long paymentOrderId);

    /**
     * Atomically update reservation status from RESERVED to CONFIRMED.
     * Returns the number of rows updated (1 if successful, 0 if already in terminal state).
     */
    @Modifying
    @Query(
            value = """
                    UPDATE billing_inventory_reservation
                    SET status = 'CONFIRMED',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE payment_order_id = :paymentOrderId
                      AND status = 'RESERVED'
                    """,
            nativeQuery = true
    )
    int confirmIfReserved(@Param("paymentOrderId") Long paymentOrderId);

    /**
     * Atomically update reservation status from RESERVED to RELEASED.
     * Returns the number of rows updated (1 if successful, 0 if already in terminal state).
     */
    @Modifying
    @Query(
            value = """
                    UPDATE billing_inventory_reservation
                    SET status = 'RELEASED',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE payment_order_id = :paymentOrderId
                      AND status = 'RESERVED'
                    """,
            nativeQuery = true
    )
    int releaseIfReserved(@Param("paymentOrderId") Long paymentOrderId);

    /**
     * Check if an active reservation exists for a payment order.
     */
    @Query(
            value = """
                    SELECT COUNT(*) > 0
                    FROM billing_inventory_reservation
                    WHERE payment_order_id = :paymentOrderId
                      AND status = 'RESERVED'
                    """,
            nativeQuery = true
    )
    boolean existsActiveReservationByPaymentOrderId(@Param("paymentOrderId") Long paymentOrderId);

    /**
     * Find orphan RESERVED reservations created before the cutoff timestamp.
     * Ordered by creation time for deterministic recovery.
     */
    @Query(
            value = """
                    SELECT r.*
                    FROM billing_inventory_reservation r
                    WHERE r.status = 'RESERVED'
                      AND r.created_at < :cutoffTimestamp
                    ORDER BY r.created_at ASC, r.id ASC
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<BillingInventoryReservation> findOrphanReservedReservations(
            @Param("cutoffTimestamp") LocalDateTime cutoffTimestamp,
            @Param("limit") int limit
    );

    /**
     * Atomically release orphan reservation by ID if still RESERVED.
     * Returns the number of rows updated (1 if successful, 0 if already in terminal state).
     */
    @Modifying
    @Query(
            value = """
                    UPDATE billing_inventory_reservation
                    SET status = 'RELEASED',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :reservationId
                      AND status = 'RESERVED'
                    """,
            nativeQuery = true
    )
    int releaseByIdIfReserved(@Param("reservationId") Long reservationId);
}