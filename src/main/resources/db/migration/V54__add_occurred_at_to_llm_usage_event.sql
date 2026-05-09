-- Add occurred_at column to llm_usage_event for business occurrence time
-- This separates business time from database write time (created_at)

-- Step 1: Add column as nullable first
ALTER TABLE llm_usage_event ADD COLUMN occurred_at TIMESTAMP;

-- Step 2: Backfill historical data with most reliable business time source
-- Priority: 1) billing_event.occurred_at (most reliable for charged usage)
--           2) llm_usage_event.created_at (fallback for uncharged history)
UPDATE llm_usage_event e
SET occurred_at = COALESCE(
    (
        SELECT b.occurred_at
        FROM billing_event b
        WHERE b.source_type = 'LLM_USAGE_EVENT'
          AND b.source_id = e.id::text
        ORDER BY b.id DESC
        LIMIT 1
    ),
    e.created_at
);

-- Step 3: Make column non-nullable after backfill
ALTER TABLE llm_usage_event ALTER COLUMN occurred_at SET NOT NULL;

-- Step 4: Create index for usage-history queries (user_id + occurred_at DESC)
CREATE INDEX idx_llm_usage_event_user_occurred_at
    ON llm_usage_event (user_id, occurred_at DESC, id DESC);