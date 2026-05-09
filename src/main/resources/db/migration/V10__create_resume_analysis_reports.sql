-- ============================================================
-- V10: Create resume_analysis_reports table
-- ============================================================

CREATE TABLE resume_analysis_reports
(
    id                     BIGSERIAL PRIMARY KEY,
    resume_id              BIGINT        NOT NULL REFERENCES resumes (id),
    user_id                BIGINT        NOT NULL REFERENCES users (id),
    target_job_title       VARCHAR(200),
    target_job_description TEXT,
    completeness_score     INTEGER       NOT NULL,
    match_score            INTEGER,
    clarity_score          INTEGER       NOT NULL,
    overall_score          INTEGER       NOT NULL,
    summary                TEXT,
    improvement_suggestions JSONB,
    section_analysis       JSONB,
    prompt_version         VARCHAR(50),
    model_name             VARCHAR(100),
    status                 VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    error_message          VARCHAR(500),
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at           TIMESTAMP,
    CONSTRAINT uk_resume_analysis_report UNIQUE (resume_id)
);

CREATE INDEX idx_analysis_resume_id ON resume_analysis_reports (resume_id);
CREATE INDEX idx_analysis_user_id ON resume_analysis_reports (user_id);
CREATE INDEX idx_analysis_status ON resume_analysis_reports (status);

COMMENT ON TABLE resume_analysis_reports IS '简历分析报告：存储 AI 分析的简历评估结果';
COMMENT ON COLUMN resume_analysis_reports.resume_id IS '关联的简历 ID，唯一约束确保每个简历只有一个分析报告';
COMMENT ON COLUMN resume_analysis_reports.user_id IS '用户 ID，简历所有者';
COMMENT ON COLUMN resume_analysis_reports.target_job_title IS '目标职位标题，用于匹配度分析';
COMMENT ON COLUMN resume_analysis_reports.target_job_description IS '目标职位描述，用于匹配度分析';
COMMENT ON COLUMN resume_analysis_reports.completeness_score IS '完整性评分 (0-100)，评估简历内容完整程度';
COMMENT ON COLUMN resume_analysis_reports.match_score IS '匹配度评分 (0-100)，与目标职位的匹配程度';
COMMENT ON COLUMN resume_analysis_reports.clarity_score IS '清晰度评分 (0-100)，评估简历表达清晰度';
COMMENT ON COLUMN resume_analysis_reports.overall_score IS '综合评分 (0-100)，整体评估分数';
COMMENT ON COLUMN resume_analysis_reports.summary IS '分析总结，AI 生成的简历整体评价';
COMMENT ON COLUMN resume_analysis_reports.improvement_suggestions IS '改进建议，JSONB 格式存储具体的优化建议';
COMMENT ON COLUMN resume_analysis_reports.section_analysis IS '分节分析，JSONB 格式存储各模块的详细分析结果';
COMMENT ON COLUMN resume_analysis_reports.prompt_version IS 'AI Prompt 版本号，用于追踪分析模型版本';
COMMENT ON COLUMN resume_analysis_reports.model_name IS '使用的 AI 模型名称';
COMMENT ON COLUMN resume_analysis_reports.status IS '分析状态：PENDING=待处理，PROCESSING=处理中，COMPLETED=已完成，FAILED=失败';
COMMENT ON COLUMN resume_analysis_reports.error_message IS '错误信息，分析失败时的错误描述';
COMMENT ON COLUMN resume_analysis_reports.completed_at IS '分析完成时间';
