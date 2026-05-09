-- ============================================================
-- V5: Create all indexes
-- ============================================================

-- ============================================================
-- B-Tree Indexes: User Module
-- ============================================================
CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_username ON users (username);
CREATE INDEX idx_users_status ON users (status) WHERE deleted_at IS NULL;
CREATE INDEX idx_user_roles_user_id ON user_roles (user_id);

-- ============================================================
-- B-Tree Indexes: Resume Module
-- ============================================================
CREATE INDEX idx_resumes_user_id ON resumes (user_id);
CREATE INDEX idx_resumes_status ON resumes (status);
CREATE INDEX idx_resumes_created_at ON resumes (user_id, created_at DESC);

-- ============================================================
-- B-Tree Indexes: Interview Module
-- ============================================================
CREATE INDEX idx_sessions_user_id ON interview_sessions (user_id);
CREATE INDEX idx_sessions_status ON interview_sessions (status);
CREATE INDEX idx_sessions_created_at ON interview_sessions (user_id, created_at DESC);
CREATE INDEX idx_questions_session ON interview_questions (session_id);
CREATE INDEX idx_answers_session ON interview_answers (session_id);
CREATE INDEX idx_answers_question ON interview_answers (question_id);

-- ============================================================
-- B-Tree Indexes: Knowledge Base Module
-- ============================================================
CREATE INDEX idx_kb_user_id ON knowledge_bases (user_id);
CREATE INDEX idx_kb_documents_kb_id ON kb_documents (kb_id);
CREATE INDEX idx_kb_documents_status ON kb_documents (status);
CREATE INDEX idx_chunks_document_id ON document_chunks (document_id);
CREATE INDEX idx_chunks_kb_id ON document_chunks (kb_id);
CREATE INDEX idx_qa_history_user ON qa_history (user_id, created_at DESC);
CREATE INDEX idx_qa_history_kb ON qa_history (kb_id);

-- ============================================================
-- HNSW Vector Index: Semantic Search
-- ============================================================
CREATE INDEX idx_chunks_embedding_hnsw ON document_chunks
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- ============================================================
-- GIN Indexes: JSONB Query Acceleration
-- ============================================================
CREATE INDEX idx_resumes_parsed_content ON resumes USING GIN (parsed_content);
CREATE INDEX idx_answers_eval_details ON interview_answers USING GIN (evaluation_details);
CREATE INDEX idx_chunks_metadata ON document_chunks USING GIN (metadata);
