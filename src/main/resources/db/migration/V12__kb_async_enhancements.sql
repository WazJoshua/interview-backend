-- ============================================================
-- V12: KB async ingestion schema enhancements
-- ============================================================

-- Ensure UUID function is available for backfill/default generation.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- 1) knowledge_bases.external_id
-- Safe order: add nullable -> backfill -> unique index -> NOT NULL
-- ============================================================
ALTER TABLE knowledge_bases
    ADD COLUMN IF NOT EXISTS external_id UUID;

UPDATE knowledge_bases
SET external_id = gen_random_uuid()
WHERE external_id IS NULL;

ALTER TABLE knowledge_bases
    ALTER COLUMN external_id SET DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX IF NOT EXISTS idx_kb_external_id
    ON knowledge_bases (external_id);

ALTER TABLE knowledge_bases
    ALTER COLUMN external_id SET NOT NULL;

COMMENT ON COLUMN knowledge_bases.external_id IS '对外暴露的知识库 UUID，用于 API 路径参数';

-- ============================================================
-- 2) kb_documents new columns
-- Safe order: add nullable -> backfill -> defaults -> NOT NULL
-- ============================================================
ALTER TABLE kb_documents
    ADD COLUMN IF NOT EXISTS external_id UUID,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS expected_chunk_count INTEGER,
    ADD COLUMN IF NOT EXISTS embedded_chunk_count INTEGER;

UPDATE kb_documents
SET external_id = COALESCE(external_id, gen_random_uuid()),
    updated_at = COALESCE(updated_at, CURRENT_TIMESTAMP),
    expected_chunk_count = COALESCE(expected_chunk_count, 0),
    embedded_chunk_count = COALESCE(embedded_chunk_count, 0)
WHERE external_id IS NULL
   OR updated_at IS NULL
   OR expected_chunk_count IS NULL
   OR embedded_chunk_count IS NULL;

ALTER TABLE kb_documents
    ALTER COLUMN external_id SET DEFAULT gen_random_uuid(),
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN expected_chunk_count SET DEFAULT 0,
    ALTER COLUMN embedded_chunk_count SET DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS idx_kb_documents_external_id
    ON kb_documents (external_id);

ALTER TABLE kb_documents
    ALTER COLUMN external_id SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN expected_chunk_count SET NOT NULL,
    ALTER COLUMN embedded_chunk_count SET NOT NULL;

COMMENT ON COLUMN kb_documents.external_id IS '对外暴露的文档 UUID，用于 API 路径参数';
COMMENT ON COLUMN kb_documents.updated_at IS '文档处理状态更新时间，用于心跳与超时接管';
COMMENT ON COLUMN kb_documents.expected_chunk_count IS '预期分块总数';
COMMENT ON COLUMN kb_documents.embedded_chunk_count IS '已完成向量化的分块数';

-- ============================================================
-- 3) document_chunks idempotency unique constraint
-- Pre-check duplicates before adding UNIQUE(document_id, chunk_index)
-- ============================================================
DO
$$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM document_chunks
        GROUP BY document_id, chunk_index
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V12 aborted: duplicate rows found in document_chunks (document_id, chunk_index)';
    END IF;
END
$$;

DO
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'document_chunks'::regclass
          AND conname = 'uq_document_chunk_index'
    ) THEN
        ALTER TABLE document_chunks
            ADD CONSTRAINT uq_document_chunk_index UNIQUE (document_id, chunk_index);
    END IF;
END
$$;

-- ============================================================
-- 4) kb_document_outbox table
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_document_outbox
(
    id            BIGSERIAL PRIMARY KEY,
    kb_id         BIGINT      NOT NULL REFERENCES knowledge_bases (id),
    document_id   BIGINT      NOT NULL REFERENCES kb_documents (id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL DEFAULT 'NEW',
    owner         VARCHAR(100),
    retry_count   INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at       TIMESTAMP,
    error_message VARCHAR(500)
);

DO
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'kb_document_outbox'::regclass
          AND conname = 'chk_kb_document_outbox_status'
    ) THEN
        ALTER TABLE kb_document_outbox
            ADD CONSTRAINT chk_kb_document_outbox_status
                CHECK (status IN ('NEW', 'PROCESSING', 'SENT', 'RETRY', 'FAILED'));
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_kb_outbox_status
    ON kb_document_outbox (status);
CREATE INDEX IF NOT EXISTS idx_kb_outbox_created_at
    ON kb_document_outbox (created_at);
CREATE INDEX IF NOT EXISTS idx_kb_outbox_updated_at
    ON kb_document_outbox (updated_at);
CREATE INDEX IF NOT EXISTS idx_kb_outbox_document_id
    ON kb_document_outbox (document_id);

COMMENT ON TABLE kb_document_outbox IS 'KB 文档处理 outbox：确保数据库写入与消息发布的原子性';
COMMENT ON COLUMN kb_document_outbox.status IS 'NEW=待发送, PROCESSING=处理中, SENT=已发送, RETRY=重试中, FAILED=失败';
COMMENT ON COLUMN kb_document_outbox.owner IS '当前处理者标识，用于 fencing 防止重复发布';
COMMENT ON COLUMN kb_document_outbox.updated_at IS '状态更新时间，用于超时回收';
COMMENT ON COLUMN kb_document_outbox.retry_count IS 'Outbox 发布重试计数，与文档处理重试独立';
