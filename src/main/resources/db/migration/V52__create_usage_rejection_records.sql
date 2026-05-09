CREATE TABLE usage_rejection_records
(
    id                   BIGSERIAL PRIMARY KEY,
    external_id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    dedupe_key           VARCHAR(255) NOT NULL,
    user_id              BIGINT       NOT NULL REFERENCES users (id),
    charge_bucket        VARCHAR(32)  NOT NULL,
    usage_family         VARCHAR(32)  NOT NULL,
    resource_type        VARCHAR(64)  NOT NULL,
    resource_external_id VARCHAR(128) NOT NULL,
    operation_id         VARCHAR(128) NOT NULL,
    reason_code          VARCHAR(64)  NOT NULL,
    reason_message       VARCHAR(255) NOT NULL,
    metadata             JSONB,
    occurred_at          TIMESTAMP    NOT NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_usage_rejection_records_external_id UNIQUE (external_id),
    CONSTRAINT uq_usage_rejection_records_dedupe_key UNIQUE (dedupe_key)
);

CREATE INDEX idx_usage_rejection_records_user_occurred_at
    ON usage_rejection_records (user_id, occurred_at DESC, id DESC);

CREATE INDEX idx_usage_rejection_records_bucket_occurred_at
    ON usage_rejection_records (charge_bucket, occurred_at DESC, id DESC);

CREATE INDEX idx_usage_rejection_records_reason_code
    ON usage_rejection_records (reason_code, occurred_at DESC, id DESC);
