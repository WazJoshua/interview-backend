-- ============================================================
-- V6: Add external UUID for resumes (public ID)
-- ============================================================

-- Ensure UUID function is available for backfill/default generation.
CREATE
EXTENSION IF NOT EXISTS pgcrypto;

-- 1) Add external_id column (nullable first for backfill safety)
ALTER TABLE resumes
    ADD COLUMN IF NOT EXISTS external_id UUID;

-- 2) Backfill existing rows
UPDATE resumes
SET external_id = gen_random_uuid()
WHERE external_id IS NULL;

-- 3) Add uniqueness index only when no unique index/constraint exists on external_id
DO
$$
BEGIN
    IF
NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = current_schema()
          AND tablename = 'resumes'
          AND indexdef ILIKE 'CREATE UNIQUE INDEX%'
          AND indexdef ILIKE '%(external_id)%'
    ) THEN
CREATE UNIQUE INDEX idx_resumes_external_id
    ON resumes (external_id);
END IF;
END $$;

-- 4) Enforce NOT NULL for all future rows
ALTER TABLE resumes
    ALTER COLUMN external_id SET NOT NULL;

COMMENT
ON COLUMN resumes.external_id IS '对外暴露的 UUID，用于 API 响应';
