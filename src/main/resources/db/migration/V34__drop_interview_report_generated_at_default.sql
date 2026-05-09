-- V34: Keep generated_at aligned with READY-only semantics for new interview reports

ALTER TABLE interview_reports
    ALTER COLUMN generated_at DROP DEFAULT;
