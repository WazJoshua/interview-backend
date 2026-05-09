ALTER TABLE knowledge_bases
    ALTER COLUMN embedding_model SET DEFAULT 'text-embedding-v4';

COMMENT ON COLUMN document_chunks.embedding
    IS '1536 维向量嵌入（DashScope text-embedding-v4，Phase 1 基线）';
