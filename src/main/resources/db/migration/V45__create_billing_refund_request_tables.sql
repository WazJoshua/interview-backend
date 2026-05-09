CREATE TABLE billing_refund_request
(
    id                  BIGSERIAL PRIMARY KEY,
    external_id         UUID           NOT NULL DEFAULT gen_random_uuid(),
    payment_order_id    BIGINT         NOT NULL REFERENCES payment_order (id),
    user_id             BIGINT         NOT NULL REFERENCES users (id),
    requested_amount    NUMERIC(18, 6) NOT NULL,
    currency            VARCHAR(16)    NOT NULL,
    reason              VARCHAR(512)   NOT NULL,
    status              VARCHAR(32)    NOT NULL,
    reviewed_by_user_id BIGINT REFERENCES users (id),
    review_comment      VARCHAR(512),
    reviewed_at         TIMESTAMP,
    provider_refund_ref VARCHAR(128),
    provider_status     VARCHAR(64),
    refunded_at         TIMESTAMP,
    metadata            JSONB,
    created_at          TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_billing_refund_request_external_id UNIQUE (external_id),
    CONSTRAINT chk_billing_refund_request_amount_positive CHECK (requested_amount > 0),
    CONSTRAINT chk_billing_refund_request_status CHECK (
        status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'REFUNDED', 'REQUIRES_RECONCILIATION')
    )
);

CREATE INDEX idx_billing_refund_request_user_created_at
    ON billing_refund_request (user_id, created_at DESC, id DESC);

CREATE INDEX idx_billing_refund_request_status_created_at
    ON billing_refund_request (status, created_at DESC, id DESC);

CREATE UNIQUE INDEX idx_billing_refund_request_open_per_order
    ON billing_refund_request (payment_order_id)
    WHERE status IN ('PENDING_REVIEW', 'APPROVED', 'REQUIRES_RECONCILIATION');
