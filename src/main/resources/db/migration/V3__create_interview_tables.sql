-- ============================================================
-- V3: Create interview tables
-- ============================================================

-- ============================================================
-- Table: interview_sessions
-- Description: Manage interview session lifecycle
-- ============================================================
CREATE TABLE interview_sessions
(
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    resume_id        BIGINT      REFERENCES resumes (id) ON DELETE SET NULL,
    job_title        VARCHAR(200),
    job_description  TEXT,
    difficulty_level VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    question_count   INTEGER     NOT NULL DEFAULT 10,
    duration_minutes INTEGER,
    interview_mode   VARCHAR(20) NOT NULL DEFAULT 'TEXT',
    start_time       TIMESTAMP,
    end_time         TIMESTAMP,
    total_duration   INTEGER,
    status           VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    overall_score    DECIMAL(5, 2),
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_sessions_status CHECK (
        status IN ('CREATED', 'IN_PROGRESS', 'COMPLETED', 'ABORTED')
        ),
    CONSTRAINT chk_sessions_difficulty CHECK (
        difficulty_level IN ('EASY', 'MEDIUM', 'HARD', 'EXPERT')
        ),
    CONSTRAINT chk_sessions_mode CHECK (
        interview_mode IN ('TEXT', 'VOICE', 'MIXED')
        )
);

COMMENT
ON TABLE  interview_sessions                  IS '面试会话表';
COMMENT
ON COLUMN interview_sessions.difficulty_level IS '难度: EASY, MEDIUM, HARD, EXPERT';
COMMENT
ON COLUMN interview_sessions.interview_mode   IS '面试模式: TEXT-文本, VOICE-语音, MIXED-混合';
COMMENT
ON COLUMN interview_sessions.total_duration   IS '实际面试总时长（秒）';
COMMENT
ON COLUMN interview_sessions.status           IS '状态: CREATED-已创建, IN_PROGRESS-进行中, COMPLETED-已完成, ABORTED-中断';

-- ============================================================
-- Table: interview_questions
-- Description: Store AI-generated interview questions
-- ============================================================
CREATE TABLE interview_questions
(
    id                     BIGSERIAL PRIMARY KEY,
    session_id             BIGINT      NOT NULL REFERENCES interview_sessions (id) ON DELETE CASCADE,
    question_type          VARCHAR(30) NOT NULL,
    question_content       TEXT        NOT NULL,
    expected_answer_points JSONB,
    difficulty             INTEGER     NOT NULL DEFAULT 3,
    estimated_minutes      INTEGER              DEFAULT 3,
    sequence_number        INTEGER     NOT NULL,
    created_at             TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_question_type CHECK (
        question_type IN ('BASIC', 'SKILL', 'EXPERIENCE', 'SCENARIO', 'BEHAVIORAL')
        ),
    CONSTRAINT chk_question_difficulty CHECK (difficulty BETWEEN 1 AND 5)
);

COMMENT
ON TABLE  interview_questions                          IS '面试问题表';
COMMENT
ON COLUMN interview_questions.question_type            IS '题型: BASIC-基础, SKILL-技能, EXPERIENCE-经验, SCENARIO-情景, BEHAVIORAL-行为';
COMMENT
ON COLUMN interview_questions.expected_answer_points   IS '期望回答要点 JSON 数组';
COMMENT
ON COLUMN interview_questions.difficulty               IS '难度 1-5';
COMMENT
ON COLUMN interview_questions.estimated_minutes        IS '预计回答时间（分钟）';
COMMENT
ON COLUMN interview_questions.sequence_number          IS '问题在会话中的顺序号';

-- ============================================================
-- Table: interview_answers
-- Description: Store user answers and AI evaluation results
-- ============================================================
CREATE TABLE interview_answers
(
    id                 BIGSERIAL PRIMARY KEY,
    session_id         BIGINT    NOT NULL REFERENCES interview_sessions (id) ON DELETE CASCADE,
    question_id        BIGINT    NOT NULL REFERENCES interview_questions (id) ON DELETE CASCADE,
    answer_content     TEXT,
    answer_audio_url   VARCHAR(500),
    evaluation_score   DECIMAL(5, 2),
    evaluation_details JSONB,
    reference_answer   TEXT,
    duration_seconds   INTEGER,
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_session_question UNIQUE (session_id, question_id)
);

COMMENT
ON TABLE  interview_answers                    IS '面试回答表';
COMMENT
ON COLUMN interview_answers.answer_audio_url   IS '语音回答的音频文件 URL';
COMMENT
ON COLUMN interview_answers.evaluation_details IS 'AI 评估详情: {relevance, depth, clarity, professionalism, structure, coherence}';
COMMENT
ON COLUMN interview_answers.reference_answer   IS 'AI 生成的参考答案';
COMMENT
ON COLUMN interview_answers.duration_seconds   IS '回答耗时（秒）';

-- ============================================================
-- Table: interview_reports
-- Description: Comprehensive interview evaluation reports
-- ============================================================
CREATE TABLE interview_reports
(
    id                       BIGSERIAL PRIMARY KEY,
    session_id               BIGINT    NOT NULL REFERENCES interview_sessions (id) ON DELETE CASCADE UNIQUE,
    overall_score            DECIMAL(5, 2),
    content_quality_score    DECIMAL(5, 2),
    expression_quality_score DECIMAL(5, 2),
    logic_quality_score      DECIMAL(5, 2),
    dimension_scores         JSONB,
    strengths                TEXT[],
    weaknesses               TEXT[],
    improvement_suggestions  JSONB,
    skill_assessment         JSONB,
    generated_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT
ON TABLE  interview_reports                           IS '面试评估报告表';
COMMENT
ON COLUMN interview_reports.content_quality_score     IS '内容质量评分 (40%)';
COMMENT
ON COLUMN interview_reports.expression_quality_score  IS '表达质量评分 (30%)';
COMMENT
ON COLUMN interview_reports.logic_quality_score       IS '逻辑质量评分 (30%)';
COMMENT
ON COLUMN interview_reports.strengths                 IS '优势列表';
COMMENT
ON COLUMN interview_reports.weaknesses                IS '待改进项列表';
COMMENT
ON COLUMN interview_reports.skill_assessment          IS '技能评估雷达图数据';
