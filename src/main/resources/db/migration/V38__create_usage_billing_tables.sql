CREATE TABLE llm_usage_event
(
    id                   BIGSERIAL PRIMARY KEY,
    external_id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id              BIGINT       NOT NULL REFERENCES users (id),
    usage_family         VARCHAR(32)  NOT NULL,
    purpose              VARCHAR(100) NOT NULL,
    provider             VARCHAR(100) NOT NULL,
    model_code           VARCHAR(255) NOT NULL,
    resource_type        VARCHAR(64)  NOT NULL,
    resource_external_id VARCHAR(128) NOT NULL,
    operation_id         VARCHAR(128) NOT NULL,
    request_count        BIGINT       NOT NULL DEFAULT 0,
    prompt_tokens        BIGINT,
    completion_tokens    BIGINT,
    total_tokens         BIGINT,
    cached_tokens        BIGINT,
    charge_bucket        VARCHAR(32),
    business_outcome     VARCHAR(64)  NOT NULL,
    failure_reason       VARCHAR(255),
    metadata             JSONB,
    dedupe_key           VARCHAR(512) NOT NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_llm_usage_event_external_id UNIQUE (external_id),
    CONSTRAINT uq_llm_usage_event_dedupe_key UNIQUE (dedupe_key)
);

CREATE INDEX idx_llm_usage_event_user_created_at ON llm_usage_event (user_id, created_at DESC, id DESC);
CREATE INDEX idx_llm_usage_event_purpose_created_at ON llm_usage_event (purpose, created_at DESC, id DESC);
CREATE INDEX idx_llm_usage_event_charge_bucket_created_at ON llm_usage_event (charge_bucket, created_at DESC, id DESC);

CREATE TABLE llm_usage_internal_period
(
    id                             BIGSERIAL PRIMARY KEY,
    period_type                    VARCHAR(20)   NOT NULL,
    period_start                   TIMESTAMP     NOT NULL,
    period_end                     TIMESTAMP     NOT NULL,
    provider                       VARCHAR(100)  NOT NULL,
    model_code                     VARCHAR(255)  NOT NULL,
    usage_family                   VARCHAR(32)   NOT NULL,
    purpose                        VARCHAR(100)  NOT NULL,
    total_recorded_tokens          BIGINT        NOT NULL DEFAULT 0,
    total_recorded_cached_tokens   BIGINT        NOT NULL DEFAULT 0,
    total_request_count            BIGINT        NOT NULL DEFAULT 0,
    total_chargeable_tokens        BIGINT        NOT NULL DEFAULT 0,
    total_chargeable_request_count BIGINT        NOT NULL DEFAULT 0,
    total_billed_amount            NUMERIC(18,6) NOT NULL DEFAULT 0,
    currency                       VARCHAR(16)   NOT NULL,
    created_at                     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_llm_usage_internal_period_scope UNIQUE (
        period_type,
        period_start,
        period_end,
        provider,
        model_code,
        usage_family,
        purpose
    )
);

CREATE INDEX idx_llm_usage_internal_period_period ON llm_usage_internal_period (period_type, period_start, period_end);

CREATE TABLE llm_model_catalog
(
    id           BIGSERIAL PRIMARY KEY,
    provider     VARCHAR(100) NOT NULL,
    model_code   VARCHAR(255) NOT NULL,
    usage_family VARCHAR(32)  NOT NULL,
    display_name VARCHAR(255),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    metadata     JSONB,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_llm_model_catalog_provider_model_family UNIQUE (provider, model_code, usage_family)
);

CREATE TABLE llm_model_pricing_version
(
    id                     BIGSERIAL PRIMARY KEY,
    provider               VARCHAR(100)  NOT NULL,
    model_code             VARCHAR(255)  NOT NULL,
    usage_family           VARCHAR(32)   NOT NULL,
    effective_from         TIMESTAMP     NOT NULL,
    effective_to           TIMESTAMP,
    billing_unit           VARCHAR(32)   NOT NULL,
    prompt_token_price     NUMERIC(18,6),
    completion_token_price NUMERIC(18,6),
    cached_token_price     NUMERIC(18,6),
    request_price          NUMERIC(18,6),
    currency               VARCHAR(16)   NOT NULL,
    metadata               JSONB,
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_llm_model_pricing_version_scope_from UNIQUE (provider, model_code, usage_family, effective_from)
);

CREATE INDEX idx_llm_model_pricing_version_lookup
    ON llm_model_pricing_version (provider, model_code, usage_family, effective_from, effective_to);

CREATE TABLE llm_usage_charge_ledger
(
    id                    BIGSERIAL PRIMARY KEY,
    usage_event_id        BIGINT        NOT NULL REFERENCES llm_usage_event (id),
    pricing_version_id    BIGINT REFERENCES llm_model_pricing_version (id),
    prompt_token_units    BIGINT        NOT NULL DEFAULT 0,
    completion_token_units BIGINT       NOT NULL DEFAULT 0,
    cached_token_units    BIGINT        NOT NULL DEFAULT 0,
    request_units         BIGINT        NOT NULL DEFAULT 0,
    prompt_amount         NUMERIC(18,6) NOT NULL DEFAULT 0,
    completion_amount     NUMERIC(18,6) NOT NULL DEFAULT 0,
    cached_amount         NUMERIC(18,6) NOT NULL DEFAULT 0,
    request_amount        NUMERIC(18,6) NOT NULL DEFAULT 0,
    total_amount          NUMERIC(18,6) NOT NULL DEFAULT 0,
    currency              VARCHAR(16),
    charge_status         VARCHAR(32)   NOT NULL,
    metadata              JSONB,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_llm_usage_charge_ledger_usage_event_id UNIQUE (usage_event_id)
);

CREATE INDEX idx_llm_usage_charge_ledger_status ON llm_usage_charge_ledger (charge_status, created_at DESC);

CREATE TABLE usage_credit_policy_version
(
    id                     BIGSERIAL PRIMARY KEY,
    purpose                VARCHAR(100)  NOT NULL,
    charge_bucket          VARCHAR(32)   NOT NULL,
    usage_family           VARCHAR(32)   NOT NULL,
    effective_from         TIMESTAMP     NOT NULL,
    effective_to           TIMESTAMP,
    billing_unit           VARCHAR(32)   NOT NULL,
    prompt_token_ratio     NUMERIC(18,6),
    completion_token_ratio NUMERIC(18,6),
    cached_token_ratio     NUMERIC(18,6),
    request_ratio          NUMERIC(18,6),
    metadata               JSONB,
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_usage_credit_policy_version_scope_from UNIQUE (purpose, charge_bucket, usage_family, effective_from)
);

CREATE INDEX idx_usage_credit_policy_version_lookup
    ON usage_credit_policy_version (purpose, charge_bucket, usage_family, effective_from, effective_to);

CREATE TABLE llm_usage_credit_ledger
(
    id                       BIGSERIAL PRIMARY KEY,
    usage_event_id           BIGINT      NOT NULL REFERENCES llm_usage_event (id),
    credit_policy_version_id BIGINT REFERENCES usage_credit_policy_version (id),
    charge_bucket            VARCHAR(32),
    charged_credits_micros   BIGINT,
    charge_status            VARCHAR(32) NOT NULL,
    metadata                 JSONB,
    created_at               TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_llm_usage_credit_ledger_usage_event_id UNIQUE (usage_event_id)
);

CREATE INDEX idx_llm_usage_credit_ledger_status ON llm_usage_credit_ledger (charge_status, created_at DESC);

CREATE TABLE user_credit_policy
(
    id                           BIGSERIAL PRIMARY KEY,
    user_id                      BIGINT      NOT NULL REFERENCES users (id),
    effective_from               TIMESTAMP   NOT NULL,
    effective_to                 TIMESTAMP,
    resume_credits_limit_micros  BIGINT      NOT NULL DEFAULT 0,
    kb_query_credits_limit_micros BIGINT     NOT NULL DEFAULT 0,
    kb_ingestion_credits_limit_micros BIGINT NOT NULL DEFAULT 0,
    interview_credits_limit_micros BIGINT    NOT NULL DEFAULT 0,
    created_at                   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_user_credit_policy_user_effective_from UNIQUE (user_id, effective_from)
);

CREATE INDEX idx_user_credit_policy_lookup
    ON user_credit_policy (user_id, effective_from, effective_to);

CREATE TABLE user_credit_period
(
    id                             BIGSERIAL PRIMARY KEY,
    user_id                        BIGINT      NOT NULL REFERENCES users (id),
    period_type                    VARCHAR(20) NOT NULL,
    period_start                   TIMESTAMP   NOT NULL,
    period_end                     TIMESTAMP   NOT NULL,
    resume_credits_used_micros     BIGINT      NOT NULL DEFAULT 0,
    kb_query_credits_used_micros   BIGINT      NOT NULL DEFAULT 0,
    kb_ingestion_credits_used_micros BIGINT    NOT NULL DEFAULT 0,
    interview_credits_used_micros  BIGINT      NOT NULL DEFAULT 0,
    created_at                     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_user_credit_period_scope UNIQUE (user_id, period_type, period_start, period_end)
);

CREATE INDEX idx_user_credit_period_lookup
    ON user_credit_period (user_id, period_type, period_start, period_end);
