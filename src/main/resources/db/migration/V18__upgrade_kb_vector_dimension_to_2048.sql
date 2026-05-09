DROP INDEX IF EXISTS idx_chunks_embedding_hnsw;

ALTER TABLE knowledge_bases
    ALTER COLUMN vector_dimension SET DEFAULT 2048;

UPDATE knowledge_bases
SET vector_dimension = 2048
WHERE vector_dimension = 1536;

ALTER TABLE document_chunks
    ALTER COLUMN embedding TYPE vector(2048);

COMMENT ON COLUMN document_chunks.embedding
    IS '2048 维向量嵌入（DashScope text-embedding-v4；存储列为 vector(2048)，ANN 检索使用 halfvec(2048) 表达式索引）';

CREATE INDEX idx_chunks_embedding_hnsw ON document_chunks
    USING hnsw ((embedding::halfvec(2048)) halfvec_cosine_ops)
    WITH (m = 16, ef_construction = 64);
