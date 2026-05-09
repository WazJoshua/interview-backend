-- Add occurred_at index for admin usage queries (without user_id prefix)
-- Admin queries typically don't filter by user_id, so the previous
-- (user_id, occurred_at, id) index doesn't cover their query paths.

CREATE INDEX idx_llm_usage_event_occurred_at
    ON llm_usage_event (occurred_at DESC, id DESC);