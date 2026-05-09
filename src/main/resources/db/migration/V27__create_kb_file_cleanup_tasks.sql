CREATE TABLE kb_file_cleanup_tasks
(
    id              BIGSERIAL PRIMARY KEY,
    resource_type   VARCHAR(64)   NOT NULL,
    resource_ref_id BIGINT        NOT NULL,
    storage_key     VARCHAR(1000) NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error      VARCHAR(1000),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP,
    CONSTRAINT chk_kb_file_cleanup_task_status CHECK (status IN ('PENDING', 'RETRY', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_kb_file_cleanup_tasks_status_next_attempt
    ON kb_file_cleanup_tasks (status, next_attempt_at, created_at);
