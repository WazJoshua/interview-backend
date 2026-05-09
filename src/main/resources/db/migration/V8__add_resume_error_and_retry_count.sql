-- ============================================================
-- V8: Add error handling columns to resumes table
-- ============================================================

-- Add error_message column for storing parse/analysis failure details
ALTER TABLE resumes
    ADD COLUMN IF NOT EXISTS error_message VARCHAR (500);

-- Add retry_count column for tracking retry attempts
ALTER TABLE resumes
    ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;

-- Add check constraint for retry_count range
ALTER TABLE resumes
    ADD CONSTRAINT chk_resumes_retry_count CHECK (retry_count >= 0 AND retry_count <= 10);

COMMENT
ON COLUMN resumes.error_message IS 'Error message when parse/analysis fails';
COMMENT
ON COLUMN resumes.retry_count IS 'Number of retry attempts for parsing';

-- Update analysis_status comment to match enum values
COMMENT
ON COLUMN resumes.analysis_status IS 'Analysis status: PENDING, ANALYZING, COMPLETED, FAILED';
