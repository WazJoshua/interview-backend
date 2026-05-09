-- ============================================================
-- V16: Enforce analysis status constraints
-- ============================================================

UPDATE resume_analysis_reports
SET status = 'ANALYZING'
WHERE status = 'PROCESSING';

UPDATE resume_analysis_reports
SET status = 'PENDING'
WHERE status IS NULL
   OR status NOT IN ('PENDING', 'ANALYZING', 'COMPLETED', 'FAILED');

ALTER TABLE resume_analysis_reports
    DROP CONSTRAINT IF EXISTS chk_resume_analysis_reports_status;

ALTER TABLE resume_analysis_reports
    ADD CONSTRAINT chk_resume_analysis_reports_status
        CHECK (status IN ('PENDING', 'ANALYZING', 'COMPLETED', 'FAILED'));

COMMENT ON COLUMN resume_analysis_reports.status
    IS '分析状态：PENDING=待处理，ANALYZING=处理中，COMPLETED=已完成，FAILED=失败';

UPDATE resumes
SET analysis_status = 'ANALYZING'
WHERE analysis_status = 'PROCESSING';

UPDATE resumes
SET analysis_status = 'PENDING'
WHERE analysis_status IS NULL
   OR analysis_status NOT IN ('PENDING', 'ANALYZING', 'COMPLETED', 'FAILED');

ALTER TABLE resumes
    ALTER COLUMN analysis_status SET DEFAULT 'PENDING';

ALTER TABLE resumes
    ALTER COLUMN analysis_status SET NOT NULL;

ALTER TABLE resumes
    DROP CONSTRAINT IF EXISTS chk_resumes_analysis_status;

ALTER TABLE resumes
    ADD CONSTRAINT chk_resumes_analysis_status
        CHECK (analysis_status IN ('PENDING', 'ANALYZING', 'COMPLETED', 'FAILED'));

COMMENT ON COLUMN resumes.analysis_status
    IS 'Analysis status: PENDING, ANALYZING, COMPLETED, FAILED';
