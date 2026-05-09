-- ============================================================
-- V15: Create resume_job_match_reports table
-- ============================================================

CREATE TABLE resume_job_match_reports
(
    id              BIGSERIAL PRIMARY KEY,
    resume_id       BIGINT      NOT NULL REFERENCES resumes (id),
    user_id         BIGINT      NOT NULL REFERENCES users (id),
    job_title       VARCHAR(200) NOT NULL,
    job_description TEXT        NOT NULL,
    match_score     INTEGER,
    summary         TEXT,
    strengths       JSONB,
    gaps            JSONB,
    suggestions     JSONB,
    prompt_version  VARCHAR(50),
    model_name      VARCHAR(100),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    error_message   VARCHAR(500),
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_match_resume_id ON resume_job_match_reports (resume_id);
CREATE INDEX idx_match_status ON resume_job_match_reports (status);
CREATE INDEX idx_match_user_created_at ON resume_job_match_reports (user_id, created_at DESC);
CREATE INDEX idx_match_resume_user_created_at ON resume_job_match_reports (resume_id, user_id, created_at DESC);

COMMENT ON TABLE resume_job_match_reports IS '简历 × JD 匹配度报告：支持异步并行、历史记录与软删除';
COMMENT ON COLUMN resume_job_match_reports.deleted_at IS '软删除时间；默认查询需过滤 deleted_at IS NULL';

