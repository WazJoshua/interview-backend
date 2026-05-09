-- Initialize billing_plan_inventory for existing billing_plan_version records
-- This migration ensures inventory records exist before inventory control enforcement
-- Per spec.md requirement: "Inventory rows are initialized before reservation enforcement"

-- Strategy:
-- 1. Create inventory records for all sale_enabled=true billing_plan_version
-- 2. Use large total_capacity (999999999) to represent "unlimited" for initial rollout
-- 3. Do NOT set inventory_control_enabled_at (remains NULL = control not enabled)
-- 4. Use ON CONFLICT DO NOTHING for idempotent migration

-- Insert inventory records for all saleable plan versions
-- total_capacity = 999999999 represents "unlimited capacity" for initial rollout
-- When actual inventory control is needed, admin can update to specific capacity
-- and set inventory_control_enabled_at to enable enforcement
INSERT INTO billing_plan_inventory (
    billing_plan_version_id,
    total_capacity,
    reserved_count,
    confirmed_count,
    created_at,
    updated_at
)
SELECT
    bpv.id,
    999999999,  -- "unlimited" capacity for initial rollout
    0,           -- no reservations yet
    0,           -- no confirmed sales yet
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM billing_plan_version bpv
WHERE bpv.sale_enabled = true
  AND NOT EXISTS (
      SELECT 1 FROM billing_plan_inventory bpi
      WHERE bpi.billing_plan_version_id = bpv.id
  );

-- Add comment explaining the initialization strategy
COMMENT ON COLUMN billing_plan_inventory.total_capacity IS
    'Total saleable capacity for this plan version. Value 999999999 indicates unlimited capacity during initial rollout. Update to specific value when inventory control is enabled.';

-- Log the initialization result for audit
DO $$
DECLARE
    initialized_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO initialized_count
    FROM billing_plan_inventory bpi
    INNER JOIN billing_plan_version bpv ON bpi.billing_plan_version_id = bpv.id
    WHERE bpv.sale_enabled = true;

    RAISE LOG 'V43: Initialized % billing_plan_inventory records for saleable plan versions', initialized_count;
END $$;