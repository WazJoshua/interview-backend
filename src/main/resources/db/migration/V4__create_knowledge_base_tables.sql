-- ============================================================
-- V4: Create knowledge base tables
-- ============================================================

-- ============================================================
-- Table: knowledge_bases
-- Description: User-created private knowledge bases
-- ============================================================
CREATE TABLE knowledge_bases
(
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name             VARCHAR(200) NOT NULL,
    description      TEXT,
    embedding_model  VARCHAR(100) NOT NULL DEFAULT 'text-embedding-3-small',
    vector_dimension INTEGER      NOT NULL DEFAULT 1536,
    document_count   INTEGER      NOT NULL DEFAULT 0,
    total_chunks     INTEGER      NOT NULL DEFAULT 0,
    version          INTEGER      NOT NULL DEFAULT 1,
    is_public        BOOLEAN      NOT NULL DEFAULT FALSE,
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_kb_status CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED'))
);

COMMENT
ON TABLE  knowledge_bases                  IS '知识库表';
COMMENT
ON COLUMN knowledge_bases.embedding_model  IS '向量化模型标识';
COMMENT
ON COLUMN knowledge_bases.vector_dimension IS '向量维度，与模型对应';
COMMENT
ON COLUMN knowledge_bases.document_count   IS '文档数量（冗余计数，提升查询性能）';
COMMENT
ON COLUMN knowledge_bases.total_chunks     IS '文档块总数（冗余计数）';
COMMENT
ON COLUMN knowledge_bases.version          IS '知识库版本号';
COMMENT
ON COLUMN knowledge_bases.is_public        IS '是否公开知识库';

-- ============================================================
-- Table: kb_documents
-- Description: Knowledge base document metadata and processing status
-- ============================================================
CREATE TABLE kb_documents
(
    id               BIGSERIAL PRIMARY KEY,
    kb_id            BIGINT       NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    file_name        VARCHAR(500) NOT NULL,
    file_type        VARCHAR(50),
    file_size        BIGINT,
    file_url         VARCHAR(1000),
    content_hash     VARCHAR(64),
    chunk_count      INTEGER      NOT NULL DEFAULT 0,
    chunk_strategy   VARCHAR(30)  NOT NULL DEFAULT 'FIXED_SIZE',
    version          INTEGER      NOT NULL DEFAULT 1,
    previous_version INTEGER,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message    TEXT,
    metadata         JSONB,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at     TIMESTAMP,

    CONSTRAINT chk_doc_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
        ),
    CONSTRAINT chk_chunk_strategy CHECK (
        chunk_strategy IN ('FIXED_SIZE', 'PARAGRAPH', 'SEMANTIC', 'RECURSIVE')
        )
);

COMMENT
ON TABLE  kb_documents                IS '知识库文档表';
COMMENT
ON COLUMN kb_documents.content_hash   IS 'SHA-256 文件指纹，用于去重';
COMMENT
ON COLUMN kb_documents.chunk_strategy IS '分块策略: FIXED_SIZE, PARAGRAPH, SEMANTIC, RECURSIVE';
COMMENT
ON COLUMN kb_documents.version        IS '文档版本号';
COMMENT
ON COLUMN kb_documents.previous_version IS '前一版本文档ID';
COMMENT
ON COLUMN kb_documents.error_message  IS '处理失败时的错误信息';
COMMENT
ON COLUMN kb_documents.metadata       IS '文档元数据: {author, title, pageCount, language}';

-- ============================================================
-- Table: document_chunks
-- Description: Document chunks with vector embeddings for semantic search
-- ============================================================
CREATE TABLE document_chunks
(
    id             BIGSERIAL PRIMARY KEY,
    document_id    BIGINT    NOT NULL REFERENCES kb_documents (id) ON DELETE CASCADE,
    kb_id          BIGINT    NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    content        TEXT      NOT NULL,
    embedding      VECTOR(1536),
    chunk_index    INTEGER   NOT NULL,
    start_position INTEGER,
    end_position   INTEGER,
    token_count    INTEGER,
    metadata       JSONB,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT
ON TABLE  document_chunks              IS '文档块表（向量存储）';
COMMENT
ON COLUMN document_chunks.embedding    IS '1536 维向量嵌入（OpenAI text-embedding-3-small）';
COMMENT
ON COLUMN document_chunks.chunk_index  IS '块在文档中的序号';
COMMENT
ON COLUMN document_chunks.token_count  IS 'Token 数量，用于控制 LLM 上下文长度';
COMMENT
ON COLUMN document_chunks.metadata     IS '块元数据: {section, heading, pageNumber}';

-- ============================================================
-- Table: qa_history
-- Description: Q&A interaction history and retrieval feedback
-- ============================================================
CREATE TABLE qa_history
(
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kb_id            BIGINT    REFERENCES knowledge_bases (id) ON DELETE SET NULL,
    question         TEXT      NOT NULL,
    answer           TEXT      NOT NULL,
    retrieved_chunks JSONB,
    confidence_score DECIMAL(5, 4),
    feedback_score   INTEGER,
    is_favorited     BOOLEAN   NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_feedback_score CHECK (feedback_score BETWEEN 1 AND 5)
);

COMMENT
ON TABLE  qa_history                  IS '问答历史表';
COMMENT
ON COLUMN qa_history.retrieved_chunks IS '检索到的相关文档块 ID 及相似度';
COMMENT
ON COLUMN qa_history.confidence_score IS '回答置信度 (0-1)';
COMMENT
ON COLUMN qa_history.feedback_score   IS '用户反馈评分 1-5 星';
COMMENT
ON COLUMN qa_history.is_favorited     IS '是否收藏';
