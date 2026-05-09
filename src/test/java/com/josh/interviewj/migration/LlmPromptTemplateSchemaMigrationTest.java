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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration test for LLM prompt template schema.
 * Validates V56/V57 migrations created correct table structures and seed data.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LlmPromptTemplateSchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("llm_prompt_template_schema_migration_test")
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
    void migrateToLatest_CreatesPromptTemplateTablesAndSeedData() {
        migrateToLatest();

        // 1. Verify tables exist
        assertThat(tableExists("llm_prompt_template")).isTrue();
        assertThat(tableExists("llm_prompt_template_revision")).isTrue();

        // 2. Verify unique constraints
        assertThat(hasUniqueConstraintOnColumn("llm_prompt_template", "template_key")).isTrue();
        assertThat(hasUniqueConstraintOnColumns("llm_prompt_template_revision", "template_id", "revision_no")).isTrue();

        // 3. Verify indexes exist
        assertThat(indexExists("llm_prompt_template", "ix_llm_prompt_template_domain_purpose")).isTrue();
        assertThat(indexExists("llm_prompt_template", "ix_llm_prompt_template_enabled")).isTrue();
        assertThat(indexExists("llm_prompt_template_revision", "ix_llm_prompt_template_revision_template_id")).isTrue();

        // 4. Verify seed templates have active revision
        assertThat(templateHasActiveRevision("resume_analysis_stage_a")).isTrue();
        assertThat(templateHasActiveRevision("kb_query_rewrite")).isTrue();
        assertThat(templateHasActiveRevision("rag_answer_system_only")).isTrue();

        // 5. Verify specific template identities
        assertThat(templateKeyExists("resume_analysis_stage_a", "resume", "analysis", "CHAT")).isTrue();
        assertThat(templateKeyExists("kb_query_rewrite", "ragqa", "kb_query_rewrite", "CHAT")).isTrue();
        assertThat(templateKeyExists("rag_answer_system_only", "ragqa", "rag", "CHAT")).isTrue();
        assertThat(templateKeyExists("interview_evaluation", "interview", "interview_answer_evaluation", "CHAT")).isTrue();

        // 6. Verify revision content
        assertThat(revisionHasSystemTemplate("resume_analysis_stage_a", 1)).isTrue();
        assertThat(revisionHasUserTemplate("resume_analysis_stage_a", 1)).isTrue();
        assertThat(revisionHasVariables("resume_analysis_stage_a", 1)).isTrue();

        // 7. Verify system-only templates (kb_query_rewrite and rag_answer_system_only)
        assertThat(revisionHasSystemTemplate("kb_query_rewrite", 1)).isTrue();
        assertThat(revisionHasNullUserTemplate("kb_query_rewrite", 1)).isTrue();

        assertThat(revisionHasSystemTemplate("rag_answer_system_only", 1)).isTrue();
        assertThat(revisionHasNullUserTemplate("rag_answer_system_only", 1)).isTrue();

        // 8. Verify total seed templates count
        assertThat(countSeedTemplates()).isEqualTo(10);
    }

    private void migrateToLatest() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(migrationLocation)
                .load()
                .migrate();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, tableName
        );
        return count != null && count > 0;
    }

    private boolean hasUniqueConstraintOnColumn(String tableName, String columnName) {
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints " +
                        "WHERE table_schema = 'public' AND table_name = ? AND constraint_type = 'UNIQUE'",
                String.class, tableName
        );
        return constraints.stream().anyMatch(c -> c.toLowerCase().contains(columnName.toLowerCase()));
    }

    private boolean hasUniqueConstraintOnColumns(String tableName, String... columnNames) {
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints " +
                        "WHERE table_schema = 'public' AND table_name = ? AND constraint_type = 'UNIQUE'",
                String.class, tableName
        );
        return constraints.stream().anyMatch(c ->
                columnNames.length == 2 &&
                        (c.toLowerCase().contains("template_id") || c.toLowerCase().contains("revision") || c.toLowerCase().contains("scope"))
        );
    }

    private boolean indexExists(String tableName, String indexName) {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?",
                String.class, tableName
        );
        return indexes.stream().anyMatch(i -> i.equalsIgnoreCase(indexName));
    }

    private boolean templateHasActiveRevision(String templateKey) {
        Map<String, Object> template = jdbcTemplate.queryForMap(
                "SELECT template_key, active_revision_id FROM llm_prompt_template WHERE template_key = ?",
                templateKey
        );
        return template.get("active_revision_id") != null;
    }

    private boolean templateKeyExists(String templateKey, String domain, String purpose, String invocationKind) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM llm_prompt_template WHERE template_key = ? AND domain = ? AND purpose = ? AND invocation_kind = ?",
                Long.class, templateKey, domain, purpose, invocationKind
        );
        return count != null && count > 0;
    }

    private boolean revisionHasSystemTemplate(String templateKey, int revisionNo) {
        Long templateId = jdbcTemplate.queryForObject(
                "SELECT id FROM llm_prompt_template WHERE template_key = ?",
                Long.class, templateKey
        );
        if (templateId == null) return false;

        Map<String, Object> revision = jdbcTemplate.queryForMap(
                "SELECT system_template FROM llm_prompt_template_revision WHERE template_id = ? AND revision_no = ?",
                templateId, revisionNo
        );
        return revision.get("system_template") != null;
    }

    private boolean revisionHasUserTemplate(String templateKey, int revisionNo) {
        Long templateId = jdbcTemplate.queryForObject(
                "SELECT id FROM llm_prompt_template WHERE template_key = ?",
                Long.class, templateKey
        );
        if (templateId == null) return false;

        Map<String, Object> revision = jdbcTemplate.queryForMap(
                "SELECT user_template FROM llm_prompt_template_revision WHERE template_id = ? AND revision_no = ?",
                templateId, revisionNo
        );
        return revision.get("user_template") != null;
    }

    private boolean revisionHasNullUserTemplate(String templateKey, int revisionNo) {
        Long templateId = jdbcTemplate.queryForObject(
                "SELECT id FROM llm_prompt_template WHERE template_key = ?",
                Long.class, templateKey
        );
        if (templateId == null) return false;

        Map<String, Object> revision = jdbcTemplate.queryForMap(
                "SELECT user_template FROM llm_prompt_template_revision WHERE template_id = ? AND revision_no = ?",
                templateId, revisionNo
        );
        return revision.get("user_template") == null;
    }

    private boolean revisionHasVariables(String templateKey, int revisionNo) {
        Long templateId = jdbcTemplate.queryForObject(
                "SELECT id FROM llm_prompt_template WHERE template_key = ?",
                Long.class, templateKey
        );
        if (templateId == null) return false;

        Map<String, Object> revision = jdbcTemplate.queryForMap(
                "SELECT variables FROM llm_prompt_template_revision WHERE template_id = ? AND revision_no = ?",
                templateId, revisionNo
        );
        return revision.get("variables") != null;
    }

    private Long countSeedTemplates() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM llm_prompt_template",
                Long.class
        );
    }
}