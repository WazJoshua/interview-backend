package com.josh.interviewj.mq;

import com.josh.interviewj.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RetrySourceOutboxSchemaIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void addRetrySourceOutboxIdColumnsAndUniqueConstraints() {
        assertColumnExistsAndIsNullable("resume_parse_outbox");
        assertColumnExistsAndIsNullable("resume_analysis_outbox");
        assertColumnExistsAndIsNullable("kb_document_outbox");

        assertUniqueConstraintExists("resume_parse_outbox", "uk_resume_parse_outbox_retry_source");
        assertUniqueConstraintExists("resume_analysis_outbox", "uk_resume_analysis_outbox_retry_source");
        assertUniqueConstraintExists("kb_document_outbox", "uk_kb_document_outbox_retry_source");
    }

    private void assertColumnExistsAndIsNullable(String tableName) {
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = ?
                          AND column_name = 'retry_source_outbox_id'
                        """,
                Integer.class,
                tableName
        );

        String nullable = jdbcTemplate.queryForObject(
                """
                        SELECT is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = ?
                          AND column_name = 'retry_source_outbox_id'
                        """,
                String.class,
                tableName
        );

        assertThat(columnCount).isEqualTo(1);
        assertThat(nullable).isEqualTo("YES");
    }

    private void assertUniqueConstraintExists(String tableName, String constraintName) {
        Integer constraintCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = ?
                          AND constraint_type = 'UNIQUE'
                          AND constraint_name = ?
                        """,
                Integer.class,
                tableName,
                constraintName
        );

        assertThat(constraintCount).isEqualTo(1);
    }
}
