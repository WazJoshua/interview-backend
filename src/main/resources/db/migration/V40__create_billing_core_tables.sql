CREATE TABLE billing_plan
(
    id           BIGSERIAL PRIMARY KEY,
    external_id  UUID         NOT NULL DEFAULT gen_random_uuid(),
    plan_code    VARCHAR(64)  NOT NULL,
    tier_code    VARCHAR(64)  NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    metadata     JSONB,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_billing_plan_external_id UNIQUE (external_id),
    CONSTRAINT uq_billing_plan_plan_code UNIQUE (plan_code)
);

CREATE TABLE billing_plan_version
(
    id                 BIGSERIAL PRIMARY KEY,
    external_id        UUID           NOT NULL DEFAULT gen_random_uuid(),
    billing_plan_id    BIGINT         NOT NULL REFERENCES billing_plan (id),
    version_no         INTEGER        NOT NULL,
    billing_cycle      VARCHAR(32)    NOT NULL,
    amount             NUMERIC(18, 6) NOT NULL,
    currency           VARCHAR(16)    NOT NULL,
    sale_enabled       BOOLEAN        NOT NULL DEFAULT TRUE,
    renewal_enabled    BOOLEAN        NOT NULL DEFAULT TRUE,
    effective_from     TIMESTAMP      NOT NULL,
    effective_to       TIMESTAMP,
    metadata           JSONB,
    created_at         TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_billing_plan_version_external_id UNIQUE (external_id),
    CONSTRAINT uq_billing_plan_version_plan_version_no UNIQUE (billing_plan_id, version_no),
    CONSTRAINT uq_billing_plan_version_plan_effective_from UNIQUE (billing_plan_id, effective_from)
);

CREATE INDEX idx_billing_plan_version_lookup
    ON billing_plan_version (billing_plan_id, effective_from, effective_to);

CREATE TABLE billing_plan_entitlement_item
(
    id                  BIGSERIAL PRIMARY KEY,
    billing_plan_version_id BIGINT      NOT NULL REFERENCES billing_plan_version (id),
    bucket_code         VARCHAR(32)     NOT NULL,
    grant_amount_micros BIGINT          NOT NULL,
    grant_type          VARCHAR(32)     NOT NULL,
    metadata            JSONB,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_billing_plan_entitlement_item_scope UNIQUE (billing_plan_version_id, bucket_code)
);

CREATE TABLE credit_purchase_sku
(
    id           BIGSERIAL PRIMARY KEY,
    external_id  UUID         NOT NULL DEFAULT gen_random_uuid(),
    sku_code     VARCHAR(64)  NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    metadata     JSONB,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_credit_purchase_sku_external_id UNIQUE (external_id),
    CONSTRAINT uq_credit_purchase_sku_code UNIQUE (sku_code)
);

CREATE TABLE credit_purchase_sku_version
(
    id                           BIGSERIAL PRIMARY KEY,
    external_id                  UUID           NOT NULL DEFAULT gen_random_uuid(),
    credit_purchase_sku_id       BIGINT         NOT NULL REFERENCES credit_purchase_sku (id),
    version_no                   INTEGER        NOT NULL,
    credits_amount_micros        BIGINT         NOT NULL,
    amount                       NUMERIC(18, 6) NOT NULL,
    currency                     VARCHAR(16)    NOT NULL,
    sale_enabled                 BOOLEAN        NOT NULL DEFAULT TRUE,
    effective_from               TIMESTAMP      NOT NULL,
    effective_to                 TIMESTAMP,
    metadata                     JSONB,
    created_at                   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_credit_purchase_sku_version_external_id UNIQUE (external_id),
    CONSTRAINT uq_credit_purchase_sku_version_scope UNIQUE (credit_purchase_sku_id, version_no),
    CONSTRAINT uq_credit_purchase_sku_version_effective_from UNIQUE (credit_purchase_sku_id, effective_from)
);

CREATE INDEX idx_credit_purchase_sku_version_lookup
    ON credit_purchase_sku_version (credit_purchase_sku_id, effective_from, effective_to);

CREATE TABLE subscription_contract
(
    id                       BIGSERIAL PRIMARY KEY,
    external_id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id                  BIGINT       NOT NULL REFERENCES users (id),
    billing_plan_id          BIGINT       NOT NULL REFERENCES billing_plan (id),
    billing_plan_version_id  BIGINT       NOT NULL REFERENCES billing_plan_version (id),
    provider                 VARCHAR(64),
    provider_subscription_ref VARCHAR(128),
    status                   VARCHAR(64)  NOT NULL,
    current_period_start     TIMESTAMP,
    current_period_end       TIMESTAMP,
    cancel_at_period_end     BOOLEAN      NOT NULL DEFAULT FALSE,
    next_plan_version_id     BIGINT REFERENCES billing_plan_version (id),
    grace_until              TIMESTAMP,
    metadata                 JSONB,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_subscription_contract_external_id UNIQUE (external_id),
    CONSTRAINT uq_subscription_contract_provider_ref UNIQUE (provider_subscription_ref)
);

CREATE UNIQUE INDEX idx_subscription_contract_open_unique
    ON subscription_contract (user_id)
    WHERE status IN ('PENDING_ACTIVATION', 'ACTIVE', 'PAST_DUE');

CREATE INDEX idx_subscription_contract_user_status
    ON subscription_contract (user_id, status, current_period_end, id);

CREATE TABLE payment_order
(
    id                            BIGSERIAL PRIMARY KEY,
    external_id                   UUID           NOT NULL DEFAULT gen_random_uuid(),
    order_no                      VARCHAR(64)    NOT NULL,
    user_id                       BIGINT         NOT NULL REFERENCES users (id),
    order_type                    VARCHAR(64)    NOT NULL,
    biz_ref_type                  VARCHAR(64)    NOT NULL,
    biz_ref_id                    VARCHAR(128)   NOT NULL,
    subscription_contract_id      BIGINT REFERENCES subscription_contract (id),
    locked_plan_version_id        BIGINT REFERENCES billing_plan_version (id),
    locked_purchase_sku_version_id BIGINT REFERENCES credit_purchase_sku_version (id),
    provider                      VARCHAR(64)    NOT NULL,
    amount                        NUMERIC(18, 6) NOT NULL,
    currency                      VARCHAR(16)    NOT NULL,
    status                        VARCHAR(64)    NOT NULL,
    idempotency_key               VARCHAR(255)   NOT NULL,
    provider_order_ref            VARCHAR(128),
    pricing_snapshot              JSONB          NOT NULL,
    entitlement_snapshot          JSONB          NOT NULL,
    renewal_period_start          TIMESTAMP,
    renewal_period_end            TIMESTAMP,
    expires_at                    TIMESTAMP      NOT NULL,
    paid_at                       TIMESTAMP,
    created_at                    TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_payment_order_external_id UNIQUE (external_id),
    CONSTRAINT uq_payment_order_order_no UNIQUE (order_no),
    CONSTRAINT uq_payment_order_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_payment_order_renewal_window UNIQUE (
        subscription_contract_id,
        renewal_period_start,
        renewal_period_end,
        order_type
    )
);

CREATE INDEX idx_payment_order_user_created_at
    ON payment_order (user_id, created_at DESC, id DESC);

CREATE INDEX idx_payment_order_provider_order_ref
    ON payment_order (provider, provider_order_ref);

CREATE TABLE payment_event
(
    id                  BIGSERIAL PRIMARY KEY,
    external_id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    payment_order_id    BIGINT REFERENCES payment_order (id),
    provider            VARCHAR(64)  NOT NULL,
    provider_event_id   VARCHAR(128) NOT NULL,
    provider_order_ref  VARCHAR(128),
    event_type          VARCHAR(64)  NOT NULL,
    signature_valid     BOOLEAN      NOT NULL DEFAULT FALSE,
    raw_payload         JSONB        NOT NULL,
    occurred_at         TIMESTAMP    NOT NULL,
    processed_at        TIMESTAMP,
    process_status      VARCHAR(32)  NOT NULL,
    apply_attempt_count INTEGER      NOT NULL DEFAULT 0,
    last_attempt_at     TIMESTAMP,
    last_error_code     VARCHAR(64),
    last_error_message  VARCHAR(512),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_payment_event_external_id UNIQUE (external_id),
    CONSTRAINT uq_payment_event_provider_event UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_payment_event_order_id
    ON payment_event (payment_order_id, created_at DESC, id DESC);

CREATE INDEX idx_payment_event_process_status
    ON payment_event (process_status, last_attempt_at, id);

CREATE TABLE billing_event
(
    id                 BIGSERIAL PRIMARY KEY,
    external_id        UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id            BIGINT       NOT NULL REFERENCES users (id),
    event_type         VARCHAR(64)  NOT NULL,
    source_type        VARCHAR(64)  NOT NULL,
    source_id          VARCHAR(128) NOT NULL,
    idempotency_key    VARCHAR(255) NOT NULL,
    delta_amount_micros BIGINT      NOT NULL,
    bucket_code        VARCHAR(32),
    occurred_at        TIMESTAMP    NOT NULL,
    metadata           JSONB,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_billing_event_external_id UNIQUE (external_id),
    CONSTRAINT uq_billing_event_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_billing_event_user_occurred_at
    ON billing_event (user_id, occurred_at DESC, id DESC);

CREATE TABLE subscription_quota_grant
(
    id                    BIGSERIAL PRIMARY KEY,
    external_id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    subscription_contract_id BIGINT   NOT NULL REFERENCES subscription_contract (id),
    source_billing_event_id BIGINT    NOT NULL REFERENCES billing_event (id),
    period_start          TIMESTAMP   NOT NULL,
    period_end            TIMESTAMP   NOT NULL,
    bucket_code           VARCHAR(32) NOT NULL,
    granted_amount_micros BIGINT      NOT NULL,
    used_amount_micros    BIGINT      NOT NULL DEFAULT 0,
    expired_amount_micros BIGINT      NOT NULL DEFAULT 0,
    metadata              JSONB,
    created_at            TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_subscription_quota_grant_external_id UNIQUE (external_id),
    CONSTRAINT uq_subscription_quota_grant_scope UNIQUE (
        subscription_contract_id,
        period_start,
        period_end,
        bucket_code
    )
);

CREATE INDEX idx_subscription_quota_grant_contract_period
    ON subscription_quota_grant (subscription_contract_id, period_end, bucket_code, id);

CREATE TABLE credit_lot
(
    id                      BIGSERIAL PRIMARY KEY,
    external_id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id                 BIGINT      NOT NULL REFERENCES users (id),
    source_billing_event_id BIGINT      NOT NULL REFERENCES billing_event (id),
    original_amount_micros  BIGINT      NOT NULL,
    remaining_amount_micros BIGINT      NOT NULL,
    expires_at              TIMESTAMP,
    status                  VARCHAR(32) NOT NULL,
    metadata                JSONB,
    created_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_credit_lot_external_id UNIQUE (external_id),
    CONSTRAINT uq_credit_lot_source_billing_event_id UNIQUE (source_billing_event_id)
);

CREATE INDEX idx_credit_lot_user_status_created_at
    ON credit_lot (user_id, status, created_at ASC, id ASC);

CREATE TABLE credit_wallet
(
    id                        BIGSERIAL PRIMARY KEY,
    user_id                   BIGINT    NOT NULL REFERENCES users (id),
    purchased_balance_micros  BIGINT    NOT NULL DEFAULT 0,
    created_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_credit_wallet_user_id UNIQUE (user_id)
);

CREATE TABLE billing_reconciliation_case
(
    id                       BIGSERIAL PRIMARY KEY,
    external_id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id                  BIGINT REFERENCES users (id),
    payment_order_id         BIGINT REFERENCES payment_order (id),
    payment_event_id         BIGINT REFERENCES payment_event (id),
    subscription_contract_id BIGINT REFERENCES subscription_contract (id),
    case_type                VARCHAR(64)  NOT NULL,
    status                   VARCHAR(64)  NOT NULL,
    reason_code              VARCHAR(64)  NOT NULL,
    resolution_code          VARCHAR(64),
    details                  JSONB,
    resolved_by_user_id      BIGINT REFERENCES users (id),
    resolved_at              TIMESTAMP,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_billing_reconciliation_case_external_id UNIQUE (external_id)
);

CREATE INDEX idx_billing_reconciliation_case_status
    ON billing_reconciliation_case (status, created_at DESC, id DESC);

CREATE INDEX idx_billing_reconciliation_case_order_event
    ON billing_reconciliation_case (payment_order_id, payment_event_id, id);
