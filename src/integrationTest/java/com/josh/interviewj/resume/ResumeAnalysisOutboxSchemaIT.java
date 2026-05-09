package com.josh.interviewj.resume;

import com.josh.interviewj.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the analysis outbox schema required by the resume analysis change.
 */
@SpringBootTest
class ResumeAnalysisOutboxSchemaIT extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Verifies the analysis outbox table and report retry metadata exist with expected defaults.
     */
    @Test
    void schema_CreatesAnalysisOutboxTableAndReportRetryCount() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name = 'resume_analysis_outbox'
                        """,
                Integer.class
        );

        Integer reportRetryColumnCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'resume_analysis_reports'
                          AND column_name = 'retry_count'
                        """,
                Integer.class
        );

        String outboxStatusDefault = jdbcTemplate.queryForObject(
                """
                        SELECT column_default
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'resume_analysis_outbox'
                          AND column_name = 'status'
                        """,
                String.class
        );

        String outboxRetryDefault = jdbcTemplate.queryForObject(
                """
                        SELECT column_default
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'resume_analysis_outbox'
                          AND column_name = 'retry_count'
                        """,
                String.class
        );

        String reportRetryDefault = jdbcTemplate.queryForObject(
                """
                        SELECT column_default
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'resume_analysis_reports'
                          AND column_name = 'retry_count'
                        """,
                String.class
        );

        assertEquals(1, tableCount);
        assertEquals(1, reportRetryColumnCount);
        assertNotNull(outboxStatusDefault);
        assertTrue(outboxStatusDefault.contains("NEW"));
        assertNotNull(outboxRetryDefault);
        assertTrue(outboxRetryDefault.contains("0"));
        assertNotNull(reportRetryDefault);
        assertTrue(reportRetryDefault.contains("0"));
    }
}
