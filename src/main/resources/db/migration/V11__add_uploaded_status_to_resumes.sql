-- ============================================================
-- V11: Add UPLOADED status to resumes
-- ============================================================

-- Expand allowed resume status values.
ALTER TABLE resumes
    DROP CONSTRAINT IF EXISTS chk_resumes_status;

ALTER TABLE resumes
    ADD CONSTRAINT chk_resumes_status CHECK (status IN ('UPLOADED', 'PENDING', 'PARSING', 'PARSED', 'FAILED'));

-- Keep DB default aligned with application entity default.
ALTER TABLE resumes
    ALTER COLUMN status SET DEFAULT 'UPLOADED';
