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
import java.sql.Timestamp;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KbDocumentArtifactsMigrationTest {

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
    void migrateToLatest_CreatesKbDocumentArtifactsTableAndSupportsUpsert() throws Exception {
        migrateToLatest();

        assertEquals("NO", columnIsNullable("kb_document_artifacts", "id"));
        assertEquals("NO", columnIsNullable("kb_document_artifacts", "document_id"));
        assertEquals("NO", columnIsNullable("kb_document_artifacts", "artifact_type"));
        assertEquals("NO", columnIsNullable("kb_document_artifacts", "content"));
        assertEquals("YES", columnIsNullable("kb_document_artifacts", "metadata"));
        assertEquals("NO", columnIsNullable("kb_document_artifacts", "created_at"));
        assertEquals("NO", columnIsNullable("kb_document_artifacts", "updated_at"));
        assertNotNull(columnDefault("kb_document_artifacts", "created_at"));
        assertNotNull(columnDefault("kb_document_artifacts", "updated_at"));

        assertTrue(hasConstraint("kb_document_artifacts", "kb_document_artifacts_pkey", "PRIMARY KEY"));
        assertTrue(hasConstraint("kb_document_artifacts", "uq_kb_document_artifacts_document_type", "UNIQUE"));
        assertTrue(hasConstraint("kb_document_artifacts", "fk_kb_document_artifacts_document_id", "FOREIGN KEY"));
        assertEquals("document_id", foreignKeyColumn("fk_kb_document_artifacts_document_id"));
        assertEquals("kb_documents", foreignKeyTargetTable("fk_kb_document_artifacts_document_id"));
        assertEquals("CASCADE", foreignKeyDeleteRule("fk_kb_document_artifacts_document_id"));

        Long userId = insertUser();
        Long kbId = insertKnowledgeBase(userId);
        Long documentId = insertDocument(kbId);

        jdbcTemplate.update("""
                INSERT INTO kb_document_artifacts (document_id, artifact_type, content, metadata)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (document_id, artifact_type)
                DO UPDATE SET
                    content = EXCLUDED.content,
                    metadata = EXCLUDED.metadata,
                    updated_at = CURRENT_TIMESTAMP
                """, documentId, "NORMALIZED_REVIEW_TEXT", "v1", "{\"version\":1}");

        Timestamp firstUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM kb_document_artifacts WHERE document_id = ? AND artifact_type = ?",
                Timestamp.class,
                documentId,
                "NORMALIZED_REVIEW_TEXT"
        );

        Thread.sleep(1100L);

        jdbcTemplate.update("""
                INSERT INTO kb_document_artifacts (document_id, artifact_type, content, metadata)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (document_id, artifact_type)
                DO UPDATE SET
                    content = EXCLUDED.content,
                    metadata = EXCLUDED.metadata,
                    updated_at = CURRENT_TIMESTAMP
                """, documentId, "NORMALIZED_REVIEW_TEXT", "v2", "{\"version\":2}");

        Timestamp secondUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM kb_document_artifacts WHERE document_id = ? AND artifact_type = ?",
                Timestamp.class,
                documentId,
                "NORMALIZED_REVIEW_TEXT"
        );

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document_artifacts WHERE document_id = ?",
                Integer.class,
                documentId
        ));
        assertEquals("v2", jdbcTemplate.queryForObject(
                "SELECT content FROM kb_document_artifacts WHERE document_id = ? AND artifact_type = ?",
                String.class,
                documentId,
                "NORMALIZED_REVIEW_TEXT"
        ));
        assertTrue(secondUpdatedAt.after(firstUpdatedAt));
    }

    private void migrateToLatest() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(migrationLocation)
                .load()
                .migrate();
    }

    private String columnIsNullable(String tableName, String columnName) {
        return jdbcTemplate.queryForObject("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """, String.class, tableName, columnName);
    }

    private String columnDefault(String tableName, String columnName) {
        return jdbcTemplate.queryForObject("""
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """, String.class, tableName, columnName);
    }

    private boolean hasConstraint(String tableName, String constraintName, String constraintType) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = ?
                """, Integer.class, tableName, constraintName, constraintType);
        return count != null && count > 0;
    }

    private String foreignKeyColumn(String constraintName) {
        return jdbcTemplate.queryForObject("""
                SELECT kcu.column_name
                FROM information_schema.key_column_usage kcu
                WHERE kcu.table_schema = 'public'
                  AND kcu.constraint_name = ?
                """, String.class, constraintName);
    }

    private String foreignKeyTargetTable(String constraintName) {
        return jdbcTemplate.queryForObject("""
                SELECT ccu.table_name
                FROM information_schema.constraint_column_usage ccu
                WHERE ccu.table_schema = 'public'
                  AND ccu.constraint_name = ?
                """, String.class, constraintName);
    }

    private String foreignKeyDeleteRule(String constraintName) {
        return jdbcTemplate.queryForObject("""
                SELECT rc.delete_rule
                FROM information_schema.referential_constraints rc
                WHERE rc.constraint_schema = 'public'
                  AND rc.constraint_name = ?
                """, String.class, constraintName);
    }

    private Long insertUser() {
        String username = "artifact-user-" + UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, username, username + "@example.com", "hashed", "zh-CN", "Asia/Shanghai");
    }

    private Long insertKnowledgeBase(Long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_bases (external_id, user_id, name, embedding_model, vector_dimension, status)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, UUID.randomUUID(), userId, "KB", "text-embedding-v4", 2048, "ACTIVE");
    }

    private Long insertDocument(Long kbId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO kb_documents
                (external_id, kb_id, file_name, file_type, chunk_strategy, status, expected_chunk_count, embedded_chunk_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, UUID.randomUUID(), kbId, "doc.pdf", "application/pdf", "FIXED_SIZE", "PENDING", 0, 0);
    }
}
