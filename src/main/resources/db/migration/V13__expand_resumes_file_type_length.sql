-- ============================================================
-- V13: Expand resumes.file_type length for long MIME types
-- ============================================================

ALTER TABLE resumes
    ALTER COLUMN file_type TYPE VARCHAR(255);
