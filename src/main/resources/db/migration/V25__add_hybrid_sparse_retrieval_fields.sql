-- ============================================================
-- V25: Add hybrid sparse retrieval materialization fields
-- ============================================================

ALTER TABLE document_chunks ADD COLUMN sparse_content_tsv TSVECTOR;
ALTER TABLE document_chunks ADD COLUMN sparse_entities_tsv TSVECTOR;
ALTER TABLE document_chunks ADD COLUMN sparse_exact_terms TEXT[];

ALTER TABLE kb_documents ADD COLUMN sparse_ready_version VARCHAR(32);
ALTER TABLE kb_documents ADD COLUMN sparse_ready_at TIMESTAMP;

CREATE INDEX idx_chunks_sparse_content_tsv ON document_chunks USING GIN (sparse_content_tsv);
CREATE INDEX idx_chunks_sparse_entities_tsv ON document_chunks USING GIN (sparse_entities_tsv);
CREATE INDEX idx_chunks_sparse_exact_terms ON document_chunks USING GIN (sparse_exact_terms);
