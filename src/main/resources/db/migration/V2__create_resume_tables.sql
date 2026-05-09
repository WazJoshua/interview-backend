-- ============================================================
-- V2: Create resume tables
-- ============================================================

-- ============================================================
-- Table: resumes
-- Description: Store uploaded resume files and parsed content
-- ============================================================
CREATE TABLE resumes
(
    id             BIGSERIAL PRIMARY KEY,
    external_id    UUID          NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_id        BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    file_name      VARCHAR(500)  NOT NULL,
    file_url       VARCHAR(1000) NOT NULL,
    file_type      VARCHAR(50),
    file_size      BIGINT,
    content_hash   VARCHAR(64),
    raw_text       TEXT,
    parsed_content JSONB,
    target_job     VARCHAR(200),
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_resumes_status CHECK (status IN ('PENDING', 'PARSING', 'PARSED', 'FAILED'))
);

COMMENT
ON TABLE  resumes                IS '简历表';
COMMENT
ON COLUMN resumes.external_id    IS '对外暴露的 UUID，用于 API 响应';
COMMENT
ON COLUMN resumes.file_url       IS '对象存储（MinIO）中的文件路径';
COMMENT
ON COLUMN resumes.content_hash   IS 'SHA-256 文件哈希，用于去重';
COMMENT
ON COLUMN resumes.raw_text       IS 'Tika 提取的原始文本';
COMMENT
ON COLUMN resumes.parsed_content IS '结构化解析结果 JSON: {personalInfo, education, workExperience, skills, projects}';
COMMENT
ON COLUMN resumes.target_job     IS '目标求职岗位，用于匹配度评分';
COMMENT
ON COLUMN resumes.status         IS '解析状态: PENDING-待处理, PARSING-解析中, PARSED-已完成, FAILED-失败';
