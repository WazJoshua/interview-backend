-- ============================================================
-- V7: Add missing columns to resumes table
-- ============================================================

-- Add analysis_status column for tracking resume analysis progress
ALTER TABLE resumes
    ADD COLUMN IF NOT EXISTS analysis_status VARCHAR (20) DEFAULT 'PENDING';

-- Add deleted_at column for soft delete support
ALTER TABLE resumes
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

-- Add index for soft delete queries
CREATE INDEX IF NOT EXISTS idx_resumes_deleted_at ON resumes(deleted_at);

COMMENT
ON COLUMN resumes.analysis_status IS 'Analysis status: PENDING, ANALYZING, COMPLETED, FAILED';
COMMENT
ON COLUMN resumes.deleted_at IS 'Soft delete timestamp, NULL means not deleted';
