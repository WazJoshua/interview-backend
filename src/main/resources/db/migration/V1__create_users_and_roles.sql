-- ============================================================
-- V1: Create pgvector extension, users and user_roles tables
-- ============================================================

-- Enable pgvector extension for vector storage
CREATE
EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- Table: users
-- Description: Core user information table
-- ============================================================
CREATE TABLE users
(
    id             BIGSERIAL PRIMARY KEY,
    external_id    UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    username       VARCHAR(100) NOT NULL UNIQUE,
    email          VARCHAR(200) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    nickname       VARCHAR(100),
    avatar_url     VARCHAR(500),
    phone          VARCHAR(20),
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at  TIMESTAMP,
    login_count    INTEGER      NOT NULL DEFAULT 0,
    login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until   TIMESTAMP,
    locale         VARCHAR(10)           DEFAULT 'zh-CN',
    timezone       VARCHAR(50)           DEFAULT 'Asia/Shanghai',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at     TIMESTAMP,

    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'BANNED'))
);

COMMENT
ON TABLE  users                IS '用户表';
COMMENT
ON COLUMN users.external_id    IS '对外暴露的 UUID，用于 API 响应';
COMMENT
ON COLUMN users.password_hash  IS 'BCrypt 哈希密码';
COMMENT
ON COLUMN users.status         IS '账号状态: ACTIVE-正常, INACTIVE-未激活, BANNED-封禁';
COMMENT
ON COLUMN users.last_login_at  IS '最后登录时间';
COMMENT
ON COLUMN users.login_count    IS '累计登录次数';
COMMENT
ON COLUMN users.login_attempts IS '连续登录失败次数，用于防暴力破解';
COMMENT
ON COLUMN users.locked_until   IS '账户锁定截止时间，NULL 表示未锁定';
COMMENT
ON COLUMN users.locale         IS '用户语言偏好';
COMMENT
ON COLUMN users.timezone       IS '用户时区';
COMMENT
ON COLUMN users.deleted_at     IS '软删除时间戳，NULL 表示未删除';

-- ============================================================
-- Table: user_roles
-- Description: User role association table (RBAC)
-- ============================================================
CREATE TABLE user_roles
(
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       VARCHAR(50) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_user_role UNIQUE (user_id, role),
    CONSTRAINT chk_role CHECK (role IN ('USER', 'ADMIN', 'VIP'))
);

COMMENT
ON TABLE  user_roles      IS '用户角色关联表';
COMMENT
ON COLUMN user_roles.role IS '角色: USER-普通用户, ADMIN-管理员, VIP-付费用户';
