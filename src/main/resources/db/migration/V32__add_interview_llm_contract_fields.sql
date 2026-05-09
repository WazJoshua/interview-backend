-- V32: Add interview LLM contract fields (content_locale, summary)

-- Step 1: Add content_locale to interview_sessions
ALTER TABLE interview_sessions
    ADD COLUMN IF NOT EXISTS content_locale VARCHAR(10);

-- Step 2: Add summary to interview_reports
ALTER TABLE interview_reports
    ADD COLUMN IF NOT EXISTS summary TEXT;

-- Step 3: Backfill content_locale from users.locale
-- Invalid or null values are normalized to 'zh-CN'
UPDATE interview_sessions s
SET content_locale = COALESCE(
    (CASE
        WHEN u.locale IN ('zh-CN', 'en-US') THEN u.locale
        ELSE NULL
    END),
    'zh-CN'
)
FROM users u
WHERE s.user_id = u.id
  AND s.content_locale IS NULL;

-- Step 4: Add check constraint for content_locale
ALTER TABLE interview_sessions
    ADD CONSTRAINT chk_interview_sessions_content_locale
        CHECK (content_locale IS NULL OR content_locale IN ('zh-CN', 'en-US'));