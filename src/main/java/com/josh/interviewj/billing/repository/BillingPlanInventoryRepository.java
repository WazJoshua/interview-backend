package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.BillingPlanInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BillingPlanInventoryRepository extends JpaRepository<BillingPlanInventory, Long> {

    Optional<BillingPlanInventory> findByBillingPlanVersionId(Long billingPlanVersionId);

    /**
     * Atomically reserve inventory if available.
     * Returns the number of rows updated (1 if successful, 0 if insufficient inventory).
     */
    @Modifying
    @Query(
            value = """
                    UPDATE billing_plan_inventory
                    SET reserved_count = reserved_count + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE billing_plan_version_id = :billingPlanVersionId
                      AND total_capacity - reserved_count - confirmed_count >= 1
                    """,
            nativeQuery = true
    )
    int reserveOneIfAvailable(@Param("billingPlanVersionId") Long billingPlanVersionId);

    /**
     * Atomically release a reservation (decrement reserved count).
     * Used when order expires, is canceled, or fails.
     */
    @Modifying
    @Query(
            value = """
                    UPDATE billing_plan_inventory
                    SET reserved_count = reserved_count - 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE billing_plan_version_id = :billingPlanVersionId
                      AND reserved_count > 0
                    """,
            nativeQuery = true
    )
    int releaseOneReservation(@Param("billingPlanVersionId") Long billingPlanVersionId);

    /**
     * Atomically confirm a reservation (move from reserved to confirmed).
     * Used when payment succeeds.
     */
    @Modifying
    @Query(
            value = """
                    UPDATE billing_plan_inventory
                    SET reserved_count = reserved_count - 1,
                        confirmed_count = confirmed_count + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE billing_plan_version_id = :billingPlanVersionId
                      AND reserved_count > 0
                    """,
            nativeQuery = true
    )
    int confirmOneReservation(@Param("billingPlanVersionId") Long billingPlanVersionId);
}