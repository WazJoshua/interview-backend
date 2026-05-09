ALTER TABLE resume_analysis_reports
    ADD COLUMN content_locale VARCHAR(10);

ALTER TABLE resume_job_match_reports
    ADD COLUMN content_locale VARCHAR(10);

ALTER TABLE resume_analysis_reports
    ADD CONSTRAINT chk_resume_analysis_reports_content_locale
        CHECK (content_locale IS NULL OR content_locale IN ('zh-CN', 'en-US'));

ALTER TABLE resume_job_match_reports
    ADD CONSTRAINT chk_resume_job_match_reports_content_locale
        CHECK (content_locale IS NULL OR content_locale IN ('zh-CN', 'en-US'));

COMMENT ON COLUMN resume_analysis_reports.content_locale IS '分析报告生成时使用的内容语言快照';
COMMENT ON COLUMN resume_job_match_reports.content_locale IS '匹配报告生成时使用的内容语言快照';
