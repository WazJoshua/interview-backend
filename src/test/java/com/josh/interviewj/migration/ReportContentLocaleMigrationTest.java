package com.josh.interviewj.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies migration from pre-content-locale schema to the latest schema.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportContentLocaleMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("migration_test")
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
    void migrateFromV20_KeepsHistoricalNullAndAcceptsSupportedLocales() {
        migrateTo("20");

        Long userId = insertUserWithNullLocale();
        Long resumeId = insertResume(userId);
        Long analysisReportId = insertHistoricalAnalysisReport(resumeId, userId);
        Long matchReportId = insertHistoricalMatchReport(resumeId, userId);

        migrateToLatest();

        assertNull(jdbcTemplate.queryForObject(
                "SELECT content_locale FROM resume_analysis_reports WHERE id = ?",
                String.class,
                analysisReportId
        ));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT content_locale FROM resume_job_match_reports WHERE id = ?",
                String.class,
                matchReportId
        ));

        Long secondResumeId = insertResume(userId);

        jdbcTemplate.update("""
                INSERT INTO resume_analysis_reports
                (resume_id, user_id, completeness_score, clarity_score, overall_score, status, content_locale)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, secondResumeId, userId, 80, 90, 85, "PENDING", "zh-CN");
        jdbcTemplate.update("""
                INSERT INTO resume_job_match_reports
                (resume_id, user_id, job_title, job_description, status, content_locale)
                VALUES (?, ?, ?, ?, ?, ?)
                """, secondResumeId, userId, "Backend", "JD", "PENDING", "en-US");

        assertEquals("zh-CN", jdbcTemplate.queryForObject(
                "SELECT content_locale FROM resume_analysis_reports WHERE resume_id = ?",
                String.class,
                secondResumeId
        ));
        assertEquals("en-US", jdbcTemplate.queryForObject(
                "SELECT content_locale FROM resume_job_match_reports WHERE resume_id = ?",
                String.class,
                secondResumeId
        ));

        Long thirdResumeId = insertResume(userId);

        assertThrows(Exception.class, () -> jdbcTemplate.update("""
                INSERT INTO resume_analysis_reports
                (resume_id, user_id, completeness_score, clarity_score, overall_score, status, content_locale)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, thirdResumeId, userId, 80, 90, 85, "PENDING", "fr-FR"));
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

    private Long insertUserWithNullLocale() {
        String username = "migration-user-" + UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, username, username + "@example.com", "hashed", null, "Asia/Shanghai");
    }

    private Long insertResume(Long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO resumes
                (external_id, user_id, file_name, file_url, file_type, status, analysis_status, retry_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, UUID.randomUUID(), userId, "resume.pdf", "mock://resume.pdf", "application/pdf", "PARSED", "COMPLETED", 0);
    }

    private Long insertHistoricalAnalysisReport(Long resumeId, Long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO resume_analysis_reports
                (resume_id, user_id, completeness_score, clarity_score, overall_score, status)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, resumeId, userId, 80, 90, 85, "COMPLETED");
    }

    private Long insertHistoricalMatchReport(Long resumeId, Long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO resume_job_match_reports
                (resume_id, user_id, job_title, job_description, status)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, resumeId, userId, "Backend", "JD", "COMPLETED");
    }
}
