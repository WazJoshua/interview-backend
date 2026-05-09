-- ============================================================
-- V29: Enforce interview unified chat phase 1 constraints
-- ============================================================

ALTER TABLE interview_sessions
    ALTER COLUMN external_id SET NOT NULL,
    ALTER COLUMN chat_session_id SET NOT NULL,
    ALTER COLUMN difficulty_level SET DEFAULT 'MID';

ALTER TABLE interview_questions
    ALTER COLUMN external_id SET NOT NULL,
    ALTER COLUMN question_kind SET NOT NULL,
    ALTER COLUMN question_kind SET DEFAULT 'MAIN';

ALTER TABLE interview_answers
    ALTER COLUMN external_id SET NOT NULL;

ALTER TABLE interview_reports
    ALTER COLUMN external_id SET NOT NULL,
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE interview_sessions
    ADD CONSTRAINT uq_interview_sessions_external_id UNIQUE (external_id),
    ADD CONSTRAINT uq_interview_sessions_chat_session_id UNIQUE (chat_session_id),
    ADD CONSTRAINT fk_interview_sessions_chat_session_uuid
        FOREIGN KEY (chat_session_id) REFERENCES chat_sessions (external_id),
    ADD CONSTRAINT fk_interview_sessions_current_question
        FOREIGN KEY (current_question_id) REFERENCES interview_questions (id),
    ADD CONSTRAINT chk_interview_sessions_difficulty_phase1
        CHECK (difficulty_level IN ('JUNIOR', 'MID', 'SENIOR')),
    ADD CONSTRAINT chk_interview_sessions_completion_reason
        CHECK (
            completion_reason IS NULL
            OR completion_reason IN ('COMPLETED_ALL', 'USER_EARLY_END', 'ABORTED')
        ),
    ADD CONSTRAINT chk_interview_sessions_current_branch_depth
        CHECK (current_branch_depth >= 0),
    ADD CONSTRAINT chk_interview_sessions_answered_main_question_count
        CHECK (answered_main_question_count >= 0),
    ADD CONSTRAINT chk_interview_sessions_used_follow_up_count
        CHECK (used_follow_up_count >= 0),
    ADD CONSTRAINT chk_interview_sessions_pending_follow_up_count
        CHECK (pending_follow_up_count >= 0);

ALTER TABLE interview_questions
    ADD CONSTRAINT uq_interview_questions_external_id UNIQUE (external_id),
    ADD CONSTRAINT fk_interview_questions_parent_question
        FOREIGN KEY (parent_question_id) REFERENCES interview_questions (id),
    ADD CONSTRAINT fk_interview_questions_prompt_message_uuid
        FOREIGN KEY (prompt_message_id) REFERENCES chat_messages (external_id),
    ADD CONSTRAINT chk_interview_questions_kind
        CHECK (question_kind IN ('MAIN', 'FOLLOW_UP')),
    ADD CONSTRAINT chk_interview_questions_follow_up_intent
        CHECK (follow_up_intent IS NULL OR follow_up_intent IN ('CLARIFY', 'DEEP_DIVE')),
    ADD CONSTRAINT chk_interview_questions_branch_depth
        CHECK (branch_depth >= 0),
    ADD CONSTRAINT chk_interview_questions_follow_up_shape
        CHECK (
            (question_kind = 'MAIN' AND follow_up_intent IS NULL AND branch_depth = 0)
            OR (question_kind = 'FOLLOW_UP' AND follow_up_intent IS NOT NULL AND branch_depth >= 1)
        );

ALTER TABLE interview_answers
    ADD CONSTRAINT uq_interview_answers_external_id UNIQUE (external_id),
    ADD CONSTRAINT fk_interview_answers_user_message_uuid
        FOREIGN KEY (user_message_id) REFERENCES chat_messages (external_id),
    ADD CONSTRAINT fk_interview_answers_evaluation_message_uuid
        FOREIGN KEY (evaluation_message_id) REFERENCES chat_messages (external_id);

ALTER TABLE interview_reports
    ADD CONSTRAINT uq_interview_reports_external_id UNIQUE (external_id),
    ADD CONSTRAINT fk_interview_reports_summary_message_uuid
        FOREIGN KEY (summary_message_id) REFERENCES chat_messages (external_id),
    ADD CONSTRAINT chk_interview_reports_status
        CHECK (status IN ('NOT_READY', 'GENERATING', 'READY', 'FAILED'));

CREATE INDEX idx_interview_sessions_deleted_at
    ON interview_sessions (deleted_at);

CREATE INDEX idx_interview_sessions_user_status_created_at
    ON interview_sessions (user_id, status, updated_at DESC);

CREATE INDEX idx_interview_questions_session_sequence
    ON interview_questions (session_id, sequence_number);
