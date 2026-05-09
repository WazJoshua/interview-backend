package com.josh.interviewj.interview.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InterviewPhase1SchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("interview_phase1_migration_test")
            .withUsername("test")
            .withPassword("test");

    private JdbcTemplate jdbcTemplate;
    private String migrationLocation;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        migrationLocation = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();

        jdbcTemplate.execute("DROP SCHEMA IF EXISTS public CASCADE");
        jdbcTemplate.execute("CREATE SCHEMA public");
    }

    @Test
    void migrateFromV27_BackfillsInterviewPhase1FieldsAndCreatesChatSessionBinding() {
        migrateTo("27");

        Long userId = insertUser();
        Long resumeId = insertResume(userId);
        Long sessionId = insertLegacyInterviewSession(userId, resumeId, "MEDIUM", "COMPLETED", 3);
        Long questionId = insertLegacyInterviewQuestion(sessionId, 1);
        insertLegacyInterviewAnswer(sessionId, questionId);
        insertLegacyInterviewReport(sessionId);

        migrateToLatest();

        UUID interviewId = queryUuid("SELECT external_id FROM interview_sessions WHERE id = ?", sessionId);
        UUID chatSessionId = queryUuid("SELECT chat_session_id FROM interview_sessions WHERE id = ?", sessionId);
        UUID questionExternalId = queryUuid("SELECT external_id FROM interview_questions WHERE id = ?", questionId);
        UUID answerExternalId = queryUuid("SELECT external_id FROM interview_answers WHERE session_id = ? AND question_id = ?", sessionId, questionId);
        UUID reportExternalId = queryUuid("SELECT external_id FROM interview_reports WHERE session_id = ?", sessionId);

        assertNotNull(interviewId);
        assertNotNull(chatSessionId);
        assertNotNull(questionExternalId);
        assertNotNull(answerExternalId);
        assertNotNull(reportExternalId);

        assertEquals("MID", jdbcTemplate.queryForObject(
                "SELECT difficulty_level FROM interview_sessions WHERE id = ?",
                String.class,
                sessionId
        ));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT main_question_count FROM interview_sessions WHERE id = ?",
                Integer.class,
                sessionId
        ));
        assertEquals("READY", jdbcTemplate.queryForObject(
                "SELECT status FROM interview_reports WHERE session_id = ?",
                String.class,
                sessionId
        ));
        assertEquals("MAIN", jdbcTemplate.queryForObject(
                "SELECT question_kind FROM interview_questions WHERE id = ?",
                String.class,
                questionId
        ));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT branch_depth FROM interview_questions WHERE id = ?",
                Integer.class,
                questionId
        ));
        assertEquals("INTERVIEW", jdbcTemplate.queryForObject(
                "SELECT domain_type FROM chat_sessions WHERE external_id = ?",
                String.class,
                chatSessionId
        ));
        assertEquals("INTERVIEW_SESSION", jdbcTemplate.queryForObject(
                "SELECT domain_ref_type FROM chat_sessions WHERE external_id = ?",
                String.class,
                chatSessionId
        ));
        assertEquals(interviewId, queryUuid(
                "SELECT domain_ref_external_id FROM chat_sessions WHERE external_id = ?",
                chatSessionId
        ));
    }

    @Test
    void migrateToLatest_RejectsFollowUpWithoutIntentAndDuplicateChatSessionBinding() {
        migrateToLatest();

        Long userId = insertUser();
        Long resumeId = insertResume(userId);
        UUID chatSessionId = insertChatSession(userId, UUID.randomUUID());

        Long sessionId = jdbcTemplate.queryForObject("""
                INSERT INTO interview_sessions
                (user_id, resume_id, external_id, chat_session_id, job_title, difficulty_level, interview_mode, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, userId, resumeId, UUID.randomUUID(), chatSessionId, "Backend Engineer", "MID", "TEXT", "CREATED");

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO interview_questions
                (session_id, external_id, question_type, question_content, difficulty, sequence_number, question_kind, branch_depth)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, sessionId, UUID.randomUUID(), "SKILL", "Missing follow-up intent", 3, 1, "FOLLOW_UP", 1));

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO interview_sessions
                (user_id, resume_id, external_id, chat_session_id, job_title, difficulty_level, interview_mode, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, resumeId, UUID.randomUUID(), chatSessionId, "Duplicate binding", "MID", "TEXT", "CREATED"));
    }

    @Test
    void migrateToLatest_DefaultsDifficultyLevelToMid() {
        migrateToLatest();

        Long userId = insertUser();
        Long resumeId = insertResume(userId);
        UUID interviewId = UUID.randomUUID();
        UUID chatSessionId = insertChatSession(userId, interviewId);

        Long sessionId = jdbcTemplate.queryForObject("""
                INSERT INTO interview_sessions
                (user_id, resume_id, external_id, chat_session_id, job_title, interview_mode, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, userId, resumeId, interviewId, chatSessionId, "Default difficulty", "TEXT", "CREATED");

        assertEquals("MID", jdbcTemplate.queryForObject(
                "SELECT difficulty_level FROM interview_sessions WHERE id = ?",
                String.class,
                sessionId
        ));
    }

    @Test
    void migrateFromV31_BackfillsContentLocaleAndAddsSummaryField() {
        // First migrate to V31
        migrateTo("31");

        // Insert users with different locales
        Long userIdWithZhCn = insertUser();
        Long userIdWithEnUs = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, "user-en-" + UUID.randomUUID(), "en-user@example.com", "hashed", "en-US", "Asia/Shanghai");

        Long resumeId = insertResume(userIdWithZhCn);

        // Create sessions with chat sessions
        UUID chatSessionId1 = insertChatSession(userIdWithZhCn, UUID.randomUUID());
        UUID chatSessionId2 = insertChatSession(userIdWithEnUs, UUID.randomUUID());

        Long sessionIdZhCn = jdbcTemplate.queryForObject("""
                INSERT INTO interview_sessions
                (user_id, resume_id, external_id, chat_session_id, job_title, difficulty_level, interview_mode, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, userIdWithZhCn, resumeId, UUID.randomUUID(), chatSessionId1, "Java Engineer", "MID", "TEXT", "CREATED");

        Long sessionIdEnUs = jdbcTemplate.queryForObject("""
                INSERT INTO interview_sessions
                (user_id, resume_id, external_id, chat_session_id, job_title, difficulty_level, interview_mode, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, userIdWithEnUs, resumeId, UUID.randomUUID(), chatSessionId2, "Java Engineer", "MID", "TEXT", "CREATED");

        // Migrate to V32 (latest)
        migrateToLatest();

        // Verify content_locale is backfilled correctly from users.locale
        assertEquals("zh-CN", jdbcTemplate.queryForObject(
                "SELECT content_locale FROM interview_sessions WHERE id = ?",
                String.class, sessionIdZhCn));

        assertEquals("en-US", jdbcTemplate.queryForObject(
                "SELECT content_locale FROM interview_sessions WHERE id = ?",
                String.class, sessionIdEnUs));

        // Verify content_locale constraint allows valid values
        jdbcTemplate.update("""
                UPDATE interview_sessions SET content_locale = 'zh-CN' WHERE id = ?
                """, sessionIdZhCn);

        jdbcTemplate.update("""
                UPDATE interview_sessions SET content_locale = 'en-US' WHERE id = ?
                """, sessionIdEnUs);

        // Verify summary field exists and allows NULL
        jdbcTemplate.update("""
                INSERT INTO interview_reports
                (session_id, status)
                VALUES (?, ?)
                """, sessionIdZhCn, "NOT_READY");

        // summary can be null for new reports
        jdbcTemplate.queryForObject(
                "SELECT summary FROM interview_reports WHERE session_id = ?",
                String.class, sessionIdZhCn);

        // generated_at is nullable for non-READY reports
        Timestamp generatedAt = jdbcTemplate.queryForObject(
                "SELECT generated_at FROM interview_reports WHERE session_id = ?",
                Timestamp.class,
                sessionIdZhCn
        );
        assertNull(generatedAt);
    }

    private void migrateTo(String targetVersion) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(migrationLocation)
                .target(targetVersion)
                .load()
                .migrate();
    }

    private void migrateToLatest() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(migrationLocation)
                .load()
                .migrate();
    }

    private Long insertUser() {
        String username = "interview-migration-user-" + UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, username, username + "@example.com", "hashed", "zh-CN", "Asia/Shanghai");
    }

    private Long insertResume(Long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO resumes
                (external_id, user_id, file_name, file_url, file_type, status, analysis_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, UUID.randomUUID(), userId, "resume.pdf", "mock://resume.pdf", "application/pdf", "PARSED", "PENDING");
    }

    private Long insertLegacyInterviewSession(
            Long userId,
            Long resumeId,
            String difficultyLevel,
            String status,
            int questionCount
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO interview_sessions
                (user_id, resume_id, job_title, job_description, difficulty_level, question_count, duration_minutes, interview_mode, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, userId, resumeId, "Legacy Java Engineer", "Legacy JD", difficultyLevel, questionCount, 30, "TEXT", status);
    }

    private Long insertLegacyInterviewQuestion(Long sessionId, int sequenceNumber) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO interview_questions
                (session_id, question_type, question_content, difficulty, estimated_minutes, sequence_number)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, sessionId, "SKILL", "Describe your JVM tuning process.", 3, 3, sequenceNumber);
    }

    private void insertLegacyInterviewAnswer(Long sessionId, Long questionId) {
        jdbcTemplate.update("""
                INSERT INTO interview_answers
                (session_id, question_id, answer_content, evaluation_score, evaluation_details, reference_answer, duration_seconds)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, sessionId, questionId, "I check heap, GC, and thread pools.", 81.5, "{\"clarity\":0.8}", "Reference answer", 120);
    }

    private void insertLegacyInterviewReport(Long sessionId) {
        jdbcTemplate.update("""
                INSERT INTO interview_reports
                (session_id, overall_score, content_quality_score, expression_quality_score, logic_quality_score,
                 dimension_scores, strengths, weaknesses, improvement_suggestions, skill_assessment)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                """,
                sessionId,
                82.5,
                81.0,
                80.0,
                86.0,
                "{\"depth\":82}",
                new String[]{"clear structure"},
                new String[]{"more metrics"},
                "[\"add more quantitative detail\"]",
                "{\"java\":82}"
        );
    }

    private UUID insertChatSession(Long userId, UUID interviewId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO chat_sessions
                (external_id, user_id, domain_type, domain_ref_type, domain_ref_external_id, status, title,
                 next_message_sequence, message_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING external_id
                """, UUID.class, UUID.randomUUID(), userId, "INTERVIEW", "INTERVIEW_SESSION", interviewId, "CREATED", "Interview", 1, 0);
    }

    private UUID queryUuid(String sql, Object... args) {
        String value = jdbcTemplate.queryForObject(sql, String.class, args);
        assertNotNull(value);
        return UUID.fromString(value);
    }
}
