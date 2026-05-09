-- ============================================================
-- V36: Add retry_source_outbox_id to async outbox tables
-- ============================================================

ALTER TABLE resume_parse_outbox
    ADD COLUMN IF NOT EXISTS retry_source_outbox_id BIGINT;

ALTER TABLE kb_document_outbox
    ADD COLUMN IF NOT EXISTS retry_source_outbox_id BIGINT;

ALTER TABLE resume_analysis_outbox
    ADD COLUMN IF NOT EXISTS retry_source_outbox_id BIGINT;

DO
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'resume_parse_outbox'::regclass
          AND conname = 'uk_resume_parse_outbox_retry_source'
    ) THEN
        ALTER TABLE resume_parse_outbox
            ADD CONSTRAINT uk_resume_parse_outbox_retry_source
                UNIQUE (retry_source_outbox_id);
    END IF;
END
$$;

DO
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'kb_document_outbox'::regclass
          AND conname = 'uk_kb_document_outbox_retry_source'
    ) THEN
        ALTER TABLE kb_document_outbox
            ADD CONSTRAINT uk_kb_document_outbox_retry_source
                UNIQUE (retry_source_outbox_id);
    END IF;
END
$$;

DO
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'resume_analysis_outbox'::regclass
          AND conname = 'uk_resume_analysis_outbox_retry_source'
    ) THEN
        ALTER TABLE resume_analysis_outbox
            ADD CONSTRAINT uk_resume_analysis_outbox_retry_source
                UNIQUE (retry_source_outbox_id);
    END IF;
END
$$;

COMMENT
ON COLUMN resume_parse_outbox.retry_source_outbox_id
    IS '重试补出的 fresh outbox 指向原始 outbox id；初始投递为 NULL';

COMMENT
ON COLUMN kb_document_outbox.retry_source_outbox_id
    IS '重试补出的 fresh outbox 指向原始 outbox id；初始投递为 NULL';

COMMENT
ON COLUMN resume_analysis_outbox.retry_source_outbox_id
    IS '重试补出的 fresh outbox 指向原始 outbox id；初始投递为 NULL';
