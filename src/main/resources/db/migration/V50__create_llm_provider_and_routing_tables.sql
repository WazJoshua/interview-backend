CREATE TABLE llm_provider (
    id                   BIGSERIAL PRIMARY KEY,
    provider_key         VARCHAR(100) NOT NULL,
    display_name         VARCHAR(255) NOT NULL,
    base_url             VARCHAR(512),
    template_root        VARCHAR(255),
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    default_timeout_ms   INTEGER,
    default_max_retries  INTEGER,
    supported_usage_families JSONB,
    metadata             JSONB,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at           TIMESTAMP,

    CONSTRAINT uq_llm_provider_provider_key UNIQUE (provider_key)
);

CREATE TABLE llm_provider_secret (
    id                     BIGSERIAL PRIMARY KEY,
    provider_id            BIGINT       NOT NULL,
    api_key_ciphertext     TEXT         NOT NULL,
    api_key_masked         VARCHAR(64)  NOT NULL,
    encryption_key_version VARCHAR(64)  NOT NULL,
    encryption_type        VARCHAR(32)  NOT NULL,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_llm_provider_secret_provider_id UNIQUE (provider_id),
    CONSTRAINT fk_llm_provider_secret_provider
        FOREIGN KEY (provider_id) REFERENCES llm_provider (id),
    CONSTRAINT ck_llm_provider_secret_encryption_type
        CHECK (encryption_type IN ('AES_GCM'))
);

CREATE TABLE llm_routing_policy (
    id           BIGSERIAL PRIMARY KEY,
    purpose      VARCHAR(100) NOT NULL,
    model_id      BIGINT      NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    timeout_ms   INTEGER,
    max_retries  INTEGER,
    metadata     JSONB,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_llm_routing_policy_purpose UNIQUE (purpose),
    CONSTRAINT fk_llm_routing_policy_model
        FOREIGN KEY (model_id) REFERENCES llm_model_catalog (id)
);

CREATE TABLE llm_config_version (
    singleton_key   VARCHAR(32) PRIMARY KEY,
    current_version BIGINT      NOT NULL,
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_llm_config_version_singleton_key
        CHECK (singleton_key = 'GLOBAL')
);

CREATE TABLE llm_config_change_outbox (
    id                BIGSERIAL PRIMARY KEY,
    config_version    BIGINT       NOT NULL,
    change_type       VARCHAR(64)  NOT NULL,
    payload           JSONB,
    publish_status    VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    publish_attempts  INTEGER      NOT NULL DEFAULT 0,
    published_at      TIMESTAMP,
    last_error        TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_llm_config_change_outbox_publish_status
        CHECK (publish_status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_llm_provider_enabled_deleted
    ON llm_provider (enabled, deleted_at);

CREATE INDEX idx_llm_routing_policy_model_id
    ON llm_routing_policy (model_id);

CREATE INDEX idx_llm_config_change_outbox_status_created
    ON llm_config_change_outbox (publish_status, created_at, id);

INSERT INTO llm_config_version (singleton_key, current_version)
VALUES ('GLOBAL', 1);
