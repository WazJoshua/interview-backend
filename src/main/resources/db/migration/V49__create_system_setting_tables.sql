CREATE TABLE system_setting (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    setting_key        VARCHAR(128) NOT NULL,
    value_type         VARCHAR(32)  NOT NULL,
    value_text         TEXT,
    updated_by_user_id BIGINT,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_system_setting_setting_key UNIQUE (setting_key),
    CONSTRAINT ck_system_setting_value_type
        CHECK (value_type IN ('BOOLEAN', 'STRING')),
    CONSTRAINT fk_system_setting_updated_by
        FOREIGN KEY (updated_by_user_id) REFERENCES users(id)
);

CREATE TABLE system_setting_revision (
    singleton_key   VARCHAR(32) PRIMARY KEY,
    current_revision BIGINT      NOT NULL,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_system_setting_revision_singleton_key
        CHECK (singleton_key = 'GLOBAL')
);

INSERT INTO system_setting_revision (singleton_key, current_revision)
VALUES ('GLOBAL', 1);
