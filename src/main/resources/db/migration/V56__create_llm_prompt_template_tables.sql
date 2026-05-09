-- V56: Create LLM Prompt Template tables
-- Creates llm_prompt_template (identity) and llm_prompt_template_revision (immutable revisions)

-- 1. Create llm_prompt_template table (template identity)
CREATE TABLE llm_prompt_template (
    id BIGSERIAL PRIMARY KEY,
    template_key VARCHAR(100) UNIQUE NOT NULL,
    domain VARCHAR(50) NOT NULL,
    purpose VARCHAR(100) NOT NULL,
    invocation_kind VARCHAR(20) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    active_revision_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

COMMENT ON TABLE llm_prompt_template IS 'LLM prompt template identity table - defines what template is and which revision is active';
COMMENT ON COLUMN llm_prompt_template.template_key IS 'Unique template key, e.g. resume_analysis_stage_a';
COMMENT ON COLUMN llm_prompt_template.domain IS 'Business domain: interview, resume, ragqa';
COMMENT ON COLUMN llm_prompt_template.purpose IS 'Corresponds to LLM routing purpose, for filtering and observability';
COMMENT ON COLUMN llm_prompt_template.invocation_kind IS 'Invocation type: CHAT, EMBEDDING, RERANK (v1 only CHAT)';
COMMENT ON COLUMN llm_prompt_template.active_revision_id IS 'Currently published revision ID';

-- 2. Create llm_prompt_template_revision table (immutable revisions)
CREATE TABLE llm_prompt_template_revision (
    id BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL,
    revision_no INTEGER NOT NULL,
    system_template TEXT,
    user_template TEXT,
    variables JSONB NOT NULL,
    change_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100)
);

COMMENT ON TABLE llm_prompt_template_revision IS 'Immutable revision history - each revision cannot be edited after creation';
COMMENT ON COLUMN llm_prompt_template_revision.template_id IS 'Foreign key to llm_prompt_template';
COMMENT ON COLUMN llm_prompt_template_revision.revision_no IS 'Revision number, starts from 1, unique within template scope';
COMMENT ON COLUMN llm_prompt_template_revision.variables IS 'Variable declaration JSON: [{"name":"jobTitle","required":true}]';
COMMENT ON COLUMN llm_prompt_template_revision.change_note IS 'Description of changes in this revision';

-- 3. Add unique constraint for revision scope (template_id, revision_no)
ALTER TABLE llm_prompt_template_revision
ADD CONSTRAINT uq_llm_prompt_template_revision_scope
UNIQUE (template_id, revision_no);

-- 4. Add foreign key from revision to template
ALTER TABLE llm_prompt_template_revision
ADD CONSTRAINT fk_llm_prompt_template_revision_template
FOREIGN KEY (template_id)
REFERENCES llm_prompt_template(id)
ON DELETE CASCADE;

-- 5. Add foreign key from template.active_revision_id to revision
-- Note: This is a circular reference, handled carefully
ALTER TABLE llm_prompt_template
ADD CONSTRAINT fk_llm_prompt_template_active_revision
FOREIGN KEY (active_revision_id)
REFERENCES llm_prompt_template_revision(id)
ON DELETE SET NULL;

-- 6. Create indexes for common queries
CREATE INDEX ix_llm_prompt_template_domain_purpose
ON llm_prompt_template(domain, purpose);

CREATE INDEX ix_llm_prompt_template_enabled
ON llm_prompt_template(enabled);

CREATE INDEX ix_llm_prompt_template_revision_template_id
ON llm_prompt_template_revision(template_id);