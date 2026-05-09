-- Clean up historical duplicate active payable orders before creating unique index.
-- For each user with multiple active payable orders, keep the one closest to payment
-- completion (AWAITING_CONFIRMATION > PENDING_PROVIDER > CREATED) and expire the rest.
-- Also release any inventory reservations held by the expired orders to prevent leaks.

-- Step 1: Identify duplicate orders to expire, release their inventory, and expire them.
-- All three mutations share the same CTE in a single statement.
WITH duplicates AS (
    SELECT id,
           user_id,
           ROW_NUMBER() OVER (
               PARTITION BY user_id
               ORDER BY
                   CASE status
                       WHEN 'AWAITING_CONFIRMATION' THEN 1
                       WHEN 'PENDING_PROVIDER'      THEN 2
                       WHEN 'CREATED'                THEN 3
                   END,
                   created_at DESC,
                   id DESC
           ) AS rn
    FROM payment_order
    WHERE status IN ('CREATED', 'PENDING_PROVIDER', 'AWAITING_CONFIRMATION')
      AND payable_activated_at IS NOT NULL
),
expired_ids AS (
    SELECT id FROM duplicates WHERE rn > 1
),
-- Release inventory reservations for orders being expired.
-- Captures the plan_version_id before updating so we can decrement counts.
released_reservations AS (
    UPDATE billing_inventory_reservation
    SET status = 'RELEASED',
        updated_at = NOW()
    WHERE status = 'RESERVED'
      AND payment_order_id IN (SELECT id FROM expired_ids)
    RETURNING billing_plan_version_id
),
-- Aggregate released counts per plan version.
released_counts AS (
    SELECT billing_plan_version_id, COUNT(*) AS cnt
    FROM released_reservations
    GROUP BY billing_plan_version_id
),
-- Decrement reserved_count on inventory master for each affected plan version.
inventory_update AS (
    UPDATE billing_plan_inventory
    SET reserved_count = GREATEST(reserved_count - released_counts.cnt, 0),
        updated_at = NOW()
    FROM released_counts
    WHERE billing_plan_inventory.billing_plan_version_id = released_counts.billing_plan_version_id
)
-- Finally, expire the duplicate payment orders.
UPDATE payment_order
SET status = 'EXPIRED',
    payable_activated_at = NULL,
    updated_at = NOW()
WHERE id IN (SELECT id FROM expired_ids);

-- Step 2: Enforce at most one active payable order per user.
-- Active payable = status IN (CREATED, PENDING_PROVIDER, AWAITING_CONFIRMATION)
--                 AND payable_activated_at IS NOT NULL.
-- This prevents the phantom-read race in concurrent createOrder() calls.
CREATE UNIQUE INDEX uq_payment_order_user_active_payable
    ON payment_order (user_id)
    WHERE status IN ('CREATED', 'PENDING_PROVIDER', 'AWAITING_CONFIRMATION')
      AND payable_activated_at IS NOT NULL;
