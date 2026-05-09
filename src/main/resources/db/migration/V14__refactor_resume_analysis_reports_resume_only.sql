-- ============================================================
-- V14: Refactor resume_analysis_reports to be resume-only
-- ============================================================

ALTER TABLE resume_analysis_reports
    ADD COLUMN evidence_json JSONB;

ALTER TABLE resume_analysis_reports
    DROP COLUMN IF EXISTS target_job_title;

ALTER TABLE resume_analysis_reports
    DROP COLUMN IF EXISTS target_job_description;

ALTER TABLE resume_analysis_reports
    DROP COLUMN IF EXISTS match_score;

COMMENT ON COLUMN resume_analysis_reports.evidence_json IS 'Stage A evidence JSON used as the only resume-side input for later job match';

