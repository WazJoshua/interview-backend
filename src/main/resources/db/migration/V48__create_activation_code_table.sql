CREATE TABLE activation_code (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                        VARCHAR(14)  NOT NULL,
    code_type                   VARCHAR(20)  NOT NULL,
    billing_plan_version_id     BIGINT,
    subscription_duration_days  INT,
    credit_amount_micros        BIGINT,
    status                      VARCHAR(20)  NOT NULL DEFAULT 'UNUSED',
    expires_at                  TIMESTAMPTZ,
    redeemed_by_user_id         BIGINT,
    redeemed_at                 TIMESTAMPTZ,
    billing_event_id            BIGINT,
    batch_id                    UUID,
    created_by_user_id          BIGINT       NOT NULL,
    note                        VARCHAR(500),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_activation_code_plan_version
        FOREIGN KEY (billing_plan_version_id) REFERENCES billing_plan_version(id),
    CONSTRAINT fk_activation_code_redeemed_by
        FOREIGN KEY (redeemed_by_user_id) REFERENCES users(id),
    CONSTRAINT fk_activation_code_billing_event
        FOREIGN KEY (billing_event_id) REFERENCES billing_event(id),
    CONSTRAINT fk_activation_code_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES users(id)
);

CREATE UNIQUE INDEX uk_activation_code_code ON activation_code(code);
CREATE INDEX idx_activation_code_status_expires ON activation_code(status, expires_at) WHERE status = 'UNUSED';
CREATE INDEX idx_activation_code_batch ON activation_code(batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX idx_activation_code_redeemed_by ON activation_code(redeemed_by_user_id) WHERE redeemed_by_user_id IS NOT NULL;
