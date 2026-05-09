ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_username_key;

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_email_key;

ALTER TABLE users
    ADD CONSTRAINT uq_users_username UNIQUE (username);

ALTER TABLE users
    ADD CONSTRAINT uq_users_email UNIQUE (email);

ALTER TABLE user_roles
    DROP CONSTRAINT IF EXISTS chk_role;

ALTER TABLE user_roles
    ADD CONSTRAINT chk_user_roles_role
        CHECK (role IN ('USER', 'INVITER', 'ADMIN', 'VIP'));
