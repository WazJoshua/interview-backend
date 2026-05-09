-- ============================================================
-- V24: Create unified chat substrate tables
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_sessions
(
    id                     BIGSERIAL PRIMARY KEY,
    external_id            UUID         NOT NULL,
    user_id                BIGINT       NOT NULL,
    domain_type            VARCHAR(32)  NOT NULL,
    domain_ref_type        VARCHAR(32)  NOT NULL,
    domain_ref_external_id UUID         NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    title                  VARCHAR(200) NOT NULL DEFAULT '',
    next_message_sequence  INTEGER      NOT NULL DEFAULT 1,
    message_count          INTEGER      NOT NULL DEFAULT 0,
    last_message_preview   VARCHAR(500),
    last_message_at        TIMESTAMP,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_chat_sessions_external_id UNIQUE (external_id),
    CONSTRAINT fk_chat_sessions_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS chat_messages
(
    id                    BIGSERIAL PRIMARY KEY,
    external_id           UUID        NOT NULL,
    chat_session_id       BIGINT      NOT NULL,
    role                  VARCHAR(20) NOT NULL,
    message_type          VARCHAR(32) NOT NULL,
    content               TEXT        NOT NULL,
    metadata              JSONB,
    sequence_number       INTEGER     NOT NULL,
    anchor_message_id     BIGINT,
    estimated_token_count INTEGER     NOT NULL DEFAULT 0,
    created_at            TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_chat_messages_external_id UNIQUE (external_id),
    CONSTRAINT uq_chat_messages_session_sequence UNIQUE (chat_session_id, sequence_number),
    CONSTRAINT fk_chat_messages_session_id
        FOREIGN KEY (chat_session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_messages_anchor_message_id
        FOREIGN KEY (anchor_message_id) REFERENCES chat_messages (id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS chat_events
(
    id              BIGSERIAL PRIMARY KEY,
    external_id     UUID        NOT NULL,
    chat_session_id BIGINT      NOT NULL,
    chat_message_id BIGINT,
    domain_type     VARCHAR(32) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    payload         JSONB,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_chat_events_external_id UNIQUE (external_id),
    CONSTRAINT fk_chat_events_session_id
        FOREIGN KEY (chat_session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_events_message_id
        FOREIGN KEY (chat_message_id) REFERENCES chat_messages (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_updated
    ON chat_sessions (user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_domain_ref
    ON chat_sessions (user_id, domain_type, domain_ref_type, domain_ref_external_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session_sequence
    ON chat_messages (chat_session_id, sequence_number ASC);

CREATE INDEX IF NOT EXISTS idx_chat_events_session_created
    ON chat_events (chat_session_id, created_at DESC);

COMMENT ON TABLE chat_sessions IS '统一 chat session 主表';
COMMENT ON TABLE chat_messages IS '统一 chat message 主表';
COMMENT ON TABLE chat_events IS '统一 chat event trace 表';
