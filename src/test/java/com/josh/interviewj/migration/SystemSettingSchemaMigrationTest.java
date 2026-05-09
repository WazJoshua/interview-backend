package com.josh.interviewj.migration;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemSettingSchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("system_setting_schema_migration_test")
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
    void migrateToLatest_CreatesSystemSettingTablesAndDefaultRevision() {
        migrateToLatest();

        assertThat(tableExists("system_setting")).isTrue();
        assertThat(tableExists("system_setting_revision")).isTrue();

        assertThat(hasConstraint("system_setting", "system_setting_pkey", "PRIMARY KEY")).isTrue();
        assertThat(hasConstraint("system_setting", "uq_system_setting_setting_key", "UNIQUE")).isTrue();
        assertThat(hasConstraint("system_setting", "fk_system_setting_updated_by", "FOREIGN KEY")).isTrue();
        assertThat(hasConstraint("system_setting", "ck_system_setting_value_type", "CHECK")).isTrue();
        assertThat(hasConstraint("system_setting_revision", "system_setting_revision_pkey", "PRIMARY KEY")).isTrue();
        assertThat(hasConstraint(
                "system_setting_revision",
                "ck_system_setting_revision_singleton_key",
                "CHECK"
        )).isTrue();
        assertThat(uniqueConstraintContainsColumn(
                "system_setting",
                "uq_system_setting_setting_key",
                "setting_key"
        )).isTrue();

        assertThat(columnDataType("system_setting", "updated_at")).isEqualTo("timestamp without time zone");
        assertThat(columnDefault("system_setting", "updated_at"))
                .containsIgnoringCase("current_timestamp");
        assertThat(columnDataType("system_setting_revision", "updated_at")).isEqualTo("timestamp without time zone");
        assertThat(columnDefault("system_setting_revision", "updated_at"))
                .containsIgnoringCase("current_timestamp");

        assertThat(foreignKeyColumn("fk_system_setting_updated_by")).isEqualTo("updated_by_user_id");
        assertThat(foreignKeyTargetTable("fk_system_setting_updated_by")).isEqualTo("users");

        assertThat(queryLong("""
                SELECT current_revision
                FROM system_setting_revision
                WHERE singleton_key = 'GLOBAL'
                """)).isEqualTo(1L);
        assertThat(queryLong("SELECT COUNT(*) FROM system_setting_revision")).isEqualTo(1L);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO system_setting_revision (singleton_key, current_revision)
                VALUES ('NOT_GLOBAL', 2)
                """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO system_setting (setting_key, value_type, value_text)
                VALUES ('PAYMENT_ENABLED', 'NUMBER', '1')
                """)).isInstanceOf(DataAccessException.class);
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

    private boolean uniqueConstraintContainsColumn(String tableName, String constraintName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.key_column_usage
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND constraint_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, constraintName, columnName);
        return count != null && count > 0;
    }

    private String columnDataType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject("""
                SELECT data_type
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

    private Long queryLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
}
