package com.josh.interviewj.auth.migration;

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
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InviteCodeSchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("invite_code_schema_migration_test")
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
    void migrateToLatest_CreatesInviteCodeSchemaAndHardensAuthConstraints() {
        migrateToLatest();

        assertTrue(tableExists("invite_codes"));
        assertTrue(uniqueConstraintExists("invite_codes", "uq_invite_codes_external_id"));
        assertTrue(uniqueConstraintExists("invite_codes", "uq_invite_codes_code_normalized"));
        assertTrue(uniqueConstraintExists("invite_codes", "uq_invite_codes_used_by_user_id"));
        assertTrue(foreignKeyExists("invite_codes", "fk_invite_codes_created_by_user"));
        assertTrue(foreignKeyExists("invite_codes", "fk_invite_codes_used_by_user"));
        assertTrue(indexExists("invite_codes", "idx_invite_codes_created_by_created_at"));
        assertTrue(indexExists("invite_codes", "idx_invite_codes_expires_at"));
        assertTrue(indexExists("invite_codes", "idx_invite_codes_used_at_expires_at"));
        assertTrue(checkConstraintExists("invite_codes", "chk_invite_codes_code_normalized_length"));
        assertTrue(checkConstraintExists("invite_codes", "chk_invite_codes_usage_consistency"));

        assertNotNull(deleteRule("invite_codes", "fk_invite_codes_created_by_user"));
        assertNotNull(deleteRule("invite_codes", "fk_invite_codes_used_by_user"));
        assertTrue(!"SET NULL".equals(deleteRule("invite_codes", "fk_invite_codes_created_by_user")));
        assertTrue(!"SET NULL".equals(deleteRule("invite_codes", "fk_invite_codes_used_by_user")));

        assertTrue(uniqueConstraintExists("users", "uq_users_username"));
        assertTrue(uniqueConstraintExists("users", "uq_users_email"));
        assertTrue(checkConstraintExists("user_roles", "chk_user_roles_role"));

        Long userId = insertUser("invite-schema-user");
        jdbcTemplate.update(
                "INSERT INTO user_roles (user_id, role) VALUES (?, ?)",
                userId,
                "INVITER"
        );

        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role = 'INVITER'",
                        Integer.class,
                        userId
                )
        );

        DataAccessException duplicateUsername = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO users (username, email, password_hash, locale, timezone) VALUES (?, ?, ?, ?, ?)",
                "invite-schema-user",
                "another@example.com",
                "hashed",
                "zh-CN",
                "Asia/Shanghai"
        ));
        assertTrue(rootCauseMessage(duplicateUsername).contains("uq_users_username"));

        DataAccessException duplicateEmail = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO users (username, email, password_hash, locale, timezone) VALUES (?, ?, ?, ?, ?)",
                "invite-schema-user-2",
                "invite-schema-user@example.com",
                "hashed",
                "zh-CN",
                "Asia/Shanghai"
        ));
        assertTrue(rootCauseMessage(duplicateEmail).contains("uq_users_email"));
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
                WHERE table_schema = 'public' AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean uniqueConstraintExists(String tableName, String constraintName) {
        return constraintExists(tableName, constraintName, "UNIQUE");
    }

    private boolean foreignKeyExists(String tableName, String constraintName) {
        return constraintExists(tableName, constraintName, "FOREIGN KEY");
    }

    private boolean checkConstraintExists(String tableName, String constraintName) {
        return constraintExists(tableName, constraintName, "CHECK");
    }

    private boolean constraintExists(String tableName, String constraintName, String constraintType) {
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

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = ?
                  AND indexname = ?
                """, Integer.class, tableName, indexName);
        return count != null && count > 0;
    }

    private String deleteRule(String tableName, String constraintName) {
        return jdbcTemplate.queryForObject("""
                SELECT rc.delete_rule
                FROM information_schema.referential_constraints rc
                JOIN information_schema.table_constraints tc
                  ON tc.constraint_schema = rc.constraint_schema
                 AND tc.constraint_name = rc.constraint_name
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_name = ?
                """, String.class, tableName, constraintName);
    }

    private Long insertUser(String username) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (external_id, username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, UUID.randomUUID(), username, username + "@example.com", "hashed", "zh-CN", "Asia/Shanghai");
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return Objects.toString(current.getMessage(), "");
    }
}
