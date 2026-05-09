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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatSubstrateMigrationTest {

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
    void migrateToLatest_CreatesUnifiedChatSubstrateSchema() {
        migrateToLatest();

        assertTrue(tableExists("chat_sessions"));
        assertTrue(tableExists("chat_messages"));
        assertTrue(tableExists("chat_events"));

        assertEquals("NO", columnIsNullable("chat_sessions", "external_id"));
        assertEquals("NO", columnIsNullable("chat_messages", "external_id"));
        assertEquals("NO", columnIsNullable("chat_events", "external_id"));
        assertTrue(hasUniqueConstraint("chat_sessions", "external_id"));
        assertTrue(hasUniqueConstraint("chat_messages", "external_id"));
        assertTrue(hasUniqueConstraint("chat_events", "external_id"));

        assertEquals("NO", columnIsNullable("chat_messages", "chat_session_id"));
        assertEquals("YES", columnIsNullable("chat_messages", "anchor_message_id"));
        assertEquals("YES", columnIsNullable("chat_events", "chat_message_id"));
        assertEquals("NO", columnIsNullable("chat_sessions", "title"));
        assertEquals("NO", columnIsNullable("chat_sessions", "next_message_sequence"));
        assertEquals("NO", columnIsNullable("chat_sessions", "message_count"));
        assertNotNull(columnDefault("chat_sessions", "title"));
        assertNotNull(columnDefault("chat_sessions", "next_message_sequence"));
        assertNotNull(columnDefault("chat_sessions", "message_count"));
        assertTrue(columnDefault("chat_sessions", "title").contains("''"));
        assertTrue(columnDefault("chat_sessions", "next_message_sequence").contains("1"));
        assertTrue(columnDefault("chat_sessions", "message_count").contains("0"));

        assertTrue(hasConstraint("chat_messages", "uq_chat_messages_session_sequence", "UNIQUE"));
        assertEquals("user_id", foreignKeyColumn("fk_chat_sessions_user_id"));
        assertEquals("users", foreignKeyTargetTable("fk_chat_sessions_user_id"));
        assertEquals("CASCADE", foreignKeyDeleteRule("fk_chat_sessions_user_id"));
        assertEquals("chat_session_id", foreignKeyColumn("fk_chat_messages_session_id"));
        assertEquals("chat_sessions", foreignKeyTargetTable("fk_chat_messages_session_id"));
        assertEquals("CASCADE", foreignKeyDeleteRule("fk_chat_messages_session_id"));
        assertEquals("anchor_message_id", foreignKeyColumn("fk_chat_messages_anchor_message_id"));
        assertEquals("chat_messages", foreignKeyTargetTable("fk_chat_messages_anchor_message_id"));
        assertEquals("chat_session_id", foreignKeyColumn("fk_chat_events_session_id"));
        assertEquals("chat_sessions", foreignKeyTargetTable("fk_chat_events_session_id"));
        assertEquals("chat_message_id", foreignKeyColumn("fk_chat_events_message_id"));
        assertEquals("chat_messages", foreignKeyTargetTable("fk_chat_events_message_id"));

        assertTrue(indexExists("idx_chat_sessions_user_updated"));
        assertTrue(indexExists("idx_chat_messages_session_sequence"));
    }

    private void migrateToLatest() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(migrationLocation)
                .load()
                .migrate();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
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

    private boolean hasUniqueConstraint(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_schema = ccu.constraint_schema
                 AND tc.constraint_name = ccu.constraint_name
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'UNIQUE'
                  AND ccu.column_name = ?
                """, Integer.class, tableName, columnName);
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

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = ?
                """, Integer.class, indexName);
        return count != null && count > 0;
    }
}
