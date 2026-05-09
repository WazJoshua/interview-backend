-- ============================================================
-- V23: Create KB document artifact table
-- ============================================================

CREATE TABLE IF NOT EXISTS kb_document_artifacts
(
    id            BIGSERIAL PRIMARY KEY,
    document_id   BIGINT      NOT NULL,
    artifact_type VARCHAR(64) NOT NULL,
    content       TEXT        NOT NULL,
    metadata      JSONB,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_kb_document_artifacts_document_type UNIQUE (document_id, artifact_type),
    CONSTRAINT fk_kb_document_artifacts_document_id
        FOREIGN KEY (document_id) REFERENCES kb_documents (id) ON DELETE CASCADE
);

COMMENT ON TABLE kb_document_artifacts IS '知识库文档审计 artifact 表';
COMMENT ON COLUMN kb_document_artifacts.document_id IS '所属知识库文档 ID';
COMMENT ON COLUMN kb_document_artifacts.artifact_type IS 'artifact 类型枚举值';
COMMENT ON COLUMN kb_document_artifacts.content IS 'artifact 正文内容';
COMMENT ON COLUMN kb_document_artifacts.metadata IS 'artifact 元数据';
COMMENT ON COLUMN kb_document_artifacts.created_at IS 'artifact 首次创建时间';
COMMENT ON COLUMN kb_document_artifacts.updated_at IS 'artifact 最后一次生成时间';
