CREATE TABLE admin_operation_log
(
    id              BIGSERIAL PRIMARY KEY,
    actor_user_id   BIGINT       NOT NULL REFERENCES users (id),
    action_type     VARCHAR(64)  NOT NULL,
    resource_type   VARCHAR(64)  NOT NULL,
    resource_id     VARCHAR(128) NOT NULL,
    request_id      VARCHAR(128),
    before_snapshot JSONB,
    after_snapshot  JSONB,
    metadata        JSONB,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_admin_operation_log_actor_created_at
    ON admin_operation_log (actor_user_id, created_at DESC, id DESC);

CREATE INDEX idx_admin_operation_log_resource_created_at
    ON admin_operation_log (resource_type, resource_id, created_at DESC, id DESC);
