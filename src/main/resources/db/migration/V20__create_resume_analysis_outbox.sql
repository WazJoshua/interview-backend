-- ============================================================
-- V20: Create resume_analysis_outbox and report retry metadata
-- ============================================================

ALTER TABLE resume_analysis_reports
    ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;

COMMENT
ON COLUMN resume_analysis_reports.retry_count
    IS '简历分析业务重试计数，不与简历解析 retry_count 混用';

CREATE TABLE IF NOT EXISTS resume_analysis_outbox
(
    id
    BIGSERIAL
    PRIMARY
    KEY,
    report_id
    BIGINT
    NOT
    NULL
    REFERENCES
    resume_analysis_reports
(
    id
),
    resume_id BIGINT NOT NULL REFERENCES resumes
(
    id
),
    resume_external_id UUID NOT NULL,
    status VARCHAR
(
    20
) NOT NULL DEFAULT 'NEW',
    owner VARCHAR
(
    100
),
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP,
    error_message VARCHAR
(
    500
),
    CONSTRAINT chk_resume_analysis_outbox_status
    CHECK
(
    status
    IN
(
    'NEW',
    'PROCESSING',
    'SENT',
    'RETRY',
    'FAILED'
))
    );

CREATE INDEX IF NOT EXISTS idx_resume_analysis_outbox_status_created
    ON resume_analysis_outbox (status, created_at);

CREATE INDEX IF NOT EXISTS idx_resume_analysis_outbox_updated_at
    ON resume_analysis_outbox (updated_at);

CREATE INDEX IF NOT EXISTS idx_resume_analysis_outbox_report_id
    ON resume_analysis_outbox (report_id);

CREATE INDEX IF NOT EXISTS idx_resume_analysis_outbox_resume_external_id
    ON resume_analysis_outbox (resume_external_id);

COMMENT
ON TABLE resume_analysis_outbox
    IS '简历分析可靠消息外盒，负责将 analysis 任务投递到 Redis Stream';

COMMENT
ON COLUMN resume_analysis_outbox.report_id
    IS '关联的 resume_analysis_reports 主键';

COMMENT
ON COLUMN resume_analysis_outbox.resume_id
    IS '关联的 resumes 主键';

COMMENT
ON COLUMN resume_analysis_outbox.resume_external_id
    IS '简历 external_id，用于日志与排障';

COMMENT
ON COLUMN resume_analysis_outbox.status
    IS 'Outbox 状态：NEW、PROCESSING、SENT、RETRY、FAILED';

COMMENT
ON COLUMN resume_analysis_outbox.owner
    IS '发布 claim owner，用于多实例 fencing';

COMMENT
ON COLUMN resume_analysis_outbox.retry_count
    IS '消息发布级重试计数';

COMMENT
ON COLUMN resume_analysis_outbox.sent_at
    IS '消息成功写入 Stream 的时间';

COMMENT
ON COLUMN resume_analysis_outbox.error_message
    IS '安全错误信息';
