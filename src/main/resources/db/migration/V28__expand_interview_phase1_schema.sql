-- ============================================================
-- V28: Expand interview schema for unified chat phase 1
-- ============================================================

ALTER TABLE interview_sessions
    DROP CONSTRAINT IF EXISTS chk_sessions_difficulty;

ALTER TABLE interview_sessions
    ADD COLUMN IF NOT EXISTS external_id UUID DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS chat_session_id UUID,
    ADD COLUMN IF NOT EXISTS completion_reason VARCHAR(32),
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS current_question_id BIGINT,
    ADD COLUMN IF NOT EXISTS main_question_count INTEGER,
    ADD COLUMN IF NOT EXISTS answered_main_question_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS used_follow_up_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pending_follow_up_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS current_branch_depth INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS is_completable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS running_score DECIMAL(5, 2),
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE interview_questions
    ADD COLUMN IF NOT EXISTS external_id UUID DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS question_kind VARCHAR(20) DEFAULT 'MAIN',
    ADD COLUMN IF NOT EXISTS follow_up_intent VARCHAR(20),
    ADD COLUMN IF NOT EXISTS parent_question_id BIGINT,
    ADD COLUMN IF NOT EXISTS branch_depth INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS prompt_message_id UUID;

ALTER TABLE interview_answers
    ADD COLUMN IF NOT EXISTS external_id UUID DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS user_message_id UUID,
    ADD COLUMN IF NOT EXISTS evaluation_message_id UUID;

ALTER TABLE interview_reports
    ADD COLUMN IF NOT EXISTS external_id UUID DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'NOT_READY',
    ADD COLUMN IF NOT EXISTS summary_message_id UUID,
    ADD COLUMN IF NOT EXISTS failure_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS failure_message TEXT,
    ADD COLUMN IF NOT EXISTS failed_at TIMESTAMP;

UPDATE interview_sessions
SET difficulty_level = CASE difficulty_level
                           WHEN 'EASY' THEN 'JUNIOR'
                           WHEN 'MEDIUM' THEN 'MID'
                           WHEN 'HARD' THEN 'SENIOR'
                           WHEN 'EXPERT' THEN 'SENIOR'
                           ELSE difficulty_level
    END;

UPDATE interview_sessions
SET main_question_count = COALESCE(main_question_count, question_count)
WHERE main_question_count IS NULL;

UPDATE interview_sessions session
SET answered_main_question_count = answer_counts.answer_count
FROM (
         SELECT answers.session_id, COUNT(*)::INTEGER AS answer_count
         FROM interview_answers answers
         GROUP BY answers.session_id
     ) answer_counts
WHERE session.id = answer_counts.session_id;

UPDATE interview_sessions
SET completion_reason = CASE status
                            WHEN 'COMPLETED' THEN 'COMPLETED_ALL'
                            WHEN 'ABORTED' THEN 'ABORTED'
                            ELSE completion_reason
    END
WHERE completion_reason IS NULL;

UPDATE interview_sessions
SET is_completable = CASE
                         WHEN status IN ('COMPLETED', 'ABORTED') THEN TRUE
                         ELSE FALSE
    END;

UPDATE interview_reports
SET status = 'READY'
WHERE status IS NULL OR status = 'NOT_READY';

INSERT INTO chat_sessions (
    external_id,
    user_id,
    domain_type,
    domain_ref_type,
    domain_ref_external_id,
    status,
    title,
    next_message_sequence,
    message_count,
    created_at,
    updated_at
)
SELECT
    gen_random_uuid(),
    session.user_id,
    'INTERVIEW',
    'INTERVIEW_SESSION',
    session.external_id,
    CASE session.status
        WHEN 'CREATED' THEN 'CREATED'
        WHEN 'IN_PROGRESS' THEN 'ACTIVE'
        WHEN 'COMPLETED' THEN 'COMPLETED'
        WHEN 'ABORTED' THEN 'ABORTED'
        ELSE 'CREATED'
    END,
    COALESCE(session.job_title, ''),
    1,
    0,
    session.created_at,
    COALESCE(session.end_time, session.start_time, session.created_at)
FROM interview_sessions session
LEFT JOIN chat_sessions existing
    ON existing.domain_type = 'INTERVIEW'
   AND existing.domain_ref_type = 'INTERVIEW_SESSION'
   AND existing.domain_ref_external_id = session.external_id
WHERE existing.id IS NULL;

UPDATE interview_sessions session
SET chat_session_id = chat_session.external_id
FROM chat_sessions chat_session
WHERE chat_session.domain_type = 'INTERVIEW'
  AND chat_session.domain_ref_type = 'INTERVIEW_SESSION'
  AND chat_session.domain_ref_external_id = session.external_id
  AND session.chat_session_id IS NULL;

UPDATE interview_sessions
SET updated_at = COALESCE(end_time, start_time, created_at, CURRENT_TIMESTAMP)
WHERE updated_at IS NULL;
