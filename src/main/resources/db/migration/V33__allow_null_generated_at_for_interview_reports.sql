-- V33: Align interview report generated_at with READY-only semantics

ALTER TABLE interview_reports
    ALTER COLUMN generated_at DROP NOT NULL;

UPDATE interview_reports
SET generated_at = NULL
WHERE status IN ('NOT_READY', 'GENERATING', 'FAILED');
