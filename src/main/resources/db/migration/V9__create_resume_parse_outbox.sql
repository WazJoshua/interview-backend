-- ============================================================
-- V9: Create resume_parse_outbox table
-- ============================================================

CREATE TABLE resume_parse_outbox
(
    id                 BIGSERIAL PRIMARY KEY,
    resume_id          BIGINT      NOT NULL REFERENCES resumes (id),
    resume_external_id UUID        NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'NEW',
    owner              VARCHAR(100),
    retry_count        INTEGER     NOT NULL DEFAULT 0,
    created_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at            TIMESTAMP,
    error_message      VARCHAR(500)
);

CREATE INDEX idx_outbox_status ON resume_parse_outbox (status);
CREATE INDEX idx_outbox_created_at ON resume_parse_outbox (created_at);
CREATE INDEX idx_outbox_updated_at ON resume_parse_outbox (updated_at);
CREATE INDEX idx_outbox_resume_external_id ON resume_parse_outbox (resume_external_id);

COMMENT
ON TABLE resume_parse_outbox IS 'Outbox 模式：确保数据库写入与消息发布的原子性';
COMMENT
ON COLUMN resume_parse_outbox.status IS 'NEW=待发送, PROCESSING=处理中, SENT=已发送, RETRY=重试中, FAILED=失败';
COMMENT
ON COLUMN resume_parse_outbox.owner IS '当前处理者标识，用于 fencing 防止重复发布';
COMMENT
ON COLUMN resume_parse_outbox.updated_at IS '状态更新时间，用于超时回收';
COMMENT
ON COLUMN resume_parse_outbox.retry_count IS 'Outbox 发布重试计数，与 resumes.retry_count 独立';

