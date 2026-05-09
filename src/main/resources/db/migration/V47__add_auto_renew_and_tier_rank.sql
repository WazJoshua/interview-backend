ALTER TABLE subscription_contract
    ADD COLUMN auto_renew BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN subscription_contract.auto_renew
    IS 'Whether this contract is eligible for automatic renewal. false for activation-code subscriptions.';

ALTER TABLE billing_plan
    ADD COLUMN tier_rank INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN billing_plan.tier_rank
    IS 'Plan tier ranking for upgrade comparison. Higher value = higher tier.';

-- Backfill tier_rank for existing plans using deterministic pricing signal.
-- Rationale:
-- 1) Avoid legacy plans all staying at 0 (would block activation-code subscription upgrade checks).
-- 2) Use existing catalog facts only (billing_cycle + amount), not plan-name heuristics.
-- 3) Keep truly free/empty plans at 0; paid plans get rank >= 1 where higher normalized price => higher rank.
WITH normalized_plan_price AS (
    SELECT
        bp.id AS billing_plan_id,
        MAX(
            CASE
                WHEN bpv.amount IS NULL THEN 0::NUMERIC
                WHEN bpv.billing_cycle = 'YEARLY' THEN bpv.amount / 12
                ELSE bpv.amount
            END
        ) AS normalized_price
    FROM billing_plan bp
    LEFT JOIN billing_plan_version bpv ON bpv.billing_plan_id = bp.id
    GROUP BY bp.id
),
ranked_plan AS (
    SELECT
        billing_plan_id,
        CASE
            WHEN normalized_price <= 0 THEN 0
            ELSE DENSE_RANK() OVER (ORDER BY normalized_price ASC)
        END AS computed_tier_rank
    FROM normalized_plan_price
)
UPDATE billing_plan bp
SET tier_rank = rp.computed_tier_rank
FROM ranked_plan rp
WHERE bp.id = rp.billing_plan_id;
