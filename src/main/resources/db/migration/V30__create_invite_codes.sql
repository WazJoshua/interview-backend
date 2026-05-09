CREATE TABLE invite_codes
(
    id                 BIGSERIAL PRIMARY KEY,
    external_id        UUID         NOT NULL DEFAULT gen_random_uuid(),
    code_normalized    VARCHAR(12)  NOT NULL,
    created_by_user_id BIGINT       NOT NULL,
    used_by_user_id    BIGINT,
    expires_at         TIMESTAMP    NOT NULL,
    used_at            TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_invite_codes_external_id UNIQUE (external_id),
    CONSTRAINT uq_invite_codes_code_normalized UNIQUE (code_normalized),
    CONSTRAINT uq_invite_codes_used_by_user_id UNIQUE (used_by_user_id),
    CONSTRAINT fk_invite_codes_created_by_user
        FOREIGN KEY (created_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_invite_codes_used_by_user
        FOREIGN KEY (used_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_invite_codes_code_normalized_length
        CHECK (char_length(code_normalized) = 12),
    CONSTRAINT chk_invite_codes_usage_consistency
        CHECK (
            (used_at IS NULL AND used_by_user_id IS NULL)
            OR
            (used_at IS NOT NULL AND used_by_user_id IS NOT NULL)
        )
);

CREATE INDEX idx_invite_codes_created_by_created_at
    ON invite_codes (created_by_user_id, created_at DESC);

CREATE INDEX idx_invite_codes_expires_at
    ON invite_codes (expires_at);

CREATE INDEX idx_invite_codes_used_at_expires_at
    ON invite_codes (used_at, expires_at);
