package com.josh.interviewj.auth.migration;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordResetTokenSchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("password_reset_schema_migration_test")
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
    void migrateToLatest_CreatesPasswordResetTokenSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(migrationLocation)
                .load()
                .migrate();

        assertTrue(tableExists("password_reset_tokens"));
        assertTrue(uniqueConstraintExists("password_reset_tokens", "uq_password_reset_tokens_token_hash"));
        assertTrue(indexExists("password_reset_tokens", "idx_password_reset_tokens_user_active"));
        assertTrue(foreignKeyExists("password_reset_tokens", "fk_password_reset_tokens_user"));
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
}
