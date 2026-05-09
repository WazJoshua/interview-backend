package com.josh.interviewj.usage.repository;

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
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsageBillingSchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("usage_billing_migration_test")
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
    void migrateToLatest_CreatesUsageBillingTablesAndAtomicUpserts() {
        migrateToLatest();

        assertThat(tableExists("llm_usage_event")).isTrue();
        assertThat(tableExists("llm_usage_internal_period")).isTrue();
        assertThat(tableExists("llm_model_catalog")).isTrue();
        assertThat(tableExists("llm_model_pricing_version")).isTrue();
        assertThat(tableExists("llm_usage_charge_ledger")).isTrue();
        assertThat(tableExists("usage_credit_policy_version")).isTrue();
        assertThat(tableExists("llm_usage_credit_ledger")).isTrue();
        assertThat(tableExists("user_credit_policy")).isTrue();
        assertThat(tableExists("user_credit_period")).isTrue();
        assertThat(tableExists("usage_rejection_records")).isTrue();

        assertThat(hasConstraint("llm_usage_event", "uq_llm_usage_event_dedupe_key", "UNIQUE")).isTrue();
        assertThat(hasConstraint("llm_usage_credit_ledger", "uq_llm_usage_credit_ledger_usage_event_id", "UNIQUE")).isTrue();
        assertThat(hasConstraint("usage_credit_policy_version", "uq_usage_credit_policy_version_scope_from", "UNIQUE")).isTrue();
        assertThat(hasConstraint("usage_rejection_records", "uq_usage_rejection_records_external_id", "UNIQUE")).isTrue();
        assertThat(hasConstraint("usage_rejection_records", "uq_usage_rejection_records_dedupe_key", "UNIQUE")).isTrue();

        assertThat(uniqueConstraintContainsColumn("uq_usage_credit_policy_version_scope_from", "purpose")).isTrue();
        assertThat(uniqueConstraintContainsColumn("uq_usage_rejection_records_dedupe_key", "dedupe_key")).isTrue();
        assertThat(columnType("llm_usage_charge_ledger", "prompt_amount")).isEqualTo("numeric");
        assertThat(columnType("llm_usage_charge_ledger", "completion_amount")).isEqualTo("numeric");
        assertThat(columnType("llm_usage_charge_ledger", "cached_amount")).isEqualTo("numeric");
        assertThat(columnType("llm_usage_charge_ledger", "request_amount")).isEqualTo("numeric");
        assertThat(columnType("llm_usage_charge_ledger", "total_amount")).isEqualTo("numeric");
        assertThat(columnType("llm_usage_credit_ledger", "charged_credits_micros")).isEqualTo("int8");
        assertThat(columnType("usage_rejection_records", "metadata")).isEqualTo("jsonb");
        assertThat(columnType("llm_usage_event", "business_operation_id")).isEqualTo("varchar");
        assertThat(columnType("llm_usage_event", "execution_disposition")).isEqualTo("varchar");
        assertThat(columnType("usage_rejection_records", "business_operation_id")).isEqualTo("varchar");
        assertThat(hasIndex("idx_usage_rejection_records_user_occurred_at")).isTrue();
        assertThat(hasIndex("idx_usage_rejection_records_bucket_occurred_at")).isTrue();
        assertThat(hasIndex("idx_usage_rejection_records_reason_code")).isTrue();
        assertThat(hasIndex("idx_llm_usage_event_business_operation_id")).isTrue();
        assertThat(hasIndex("idx_usage_rejection_records_business_operation_id")).isTrue();
        assertThat(hasIndex("idx_llm_usage_event_occurred_at")).isTrue();

        Long userId = insertUser();
        upsertUserCreditPeriod(userId, 125_000L, 40_000L, 0L, 0L);
        upsertUserCreditPeriod(userId, 375_000L, 60_000L, 0L, 0L);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT resume_credits_used_micros
                FROM user_credit_period
                WHERE user_id = ?
                  AND period_type = 'MONTHLY'
                """, Long.class, userId)).isEqualTo(500_000L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT kb_query_credits_used_micros
                FROM user_credit_period
                WHERE user_id = ?
                  AND period_type = 'MONTHLY'
                """, Long.class, userId)).isEqualTo(100_000L);

        upsertInternalPeriod("default", "qwen-plus", "CHAT", "analysis", 120L, 8L, 1L, 120L, 1L, "1.250000");
        upsertInternalPeriod("default", "qwen-plus", "CHAT", "analysis", 80L, 2L, 1L, 40L, 0L, "0.500000");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT total_recorded_tokens
                FROM llm_usage_internal_period
                WHERE provider = ?
                  AND model_code = ?
                  AND usage_family = ?
                  AND purpose = ?
                """, Long.class, "default", "qwen-plus", "CHAT", "analysis")).isEqualTo(200L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT total_recorded_cached_tokens
                FROM llm_usage_internal_period
                WHERE provider = ?
                  AND model_code = ?
                  AND usage_family = ?
                  AND purpose = ?
                """, Long.class, "default", "qwen-plus", "CHAT", "analysis")).isEqualTo(10L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT total_request_count
                FROM llm_usage_internal_period
                WHERE provider = ?
                  AND model_code = ?
                  AND usage_family = ?
                  AND purpose = ?
                """, Long.class, "default", "qwen-plus", "CHAT", "analysis")).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT total_chargeable_tokens
                FROM llm_usage_internal_period
                WHERE provider = ?
                  AND model_code = ?
                  AND usage_family = ?
                  AND purpose = ?
                """, Long.class, "default", "qwen-plus", "CHAT", "analysis")).isEqualTo(160L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT total_chargeable_request_count
                FROM llm_usage_internal_period
                WHERE provider = ?
                  AND model_code = ?
                  AND usage_family = ?
                  AND purpose = ?
                """, Long.class, "default", "qwen-plus", "CHAT", "analysis")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT total_billed_amount::text
                FROM llm_usage_internal_period
                WHERE provider = ?
                  AND model_code = ?
                  AND usage_family = ?
                  AND purpose = ?
                """, String.class, "default", "qwen-plus", "CHAT", "analysis")).isEqualTo("1.750000");

        Timestamp updatedAt = jdbcTemplate.queryForObject("""
                SELECT updated_at
                FROM llm_usage_internal_period
                WHERE provider = ?
                  AND model_code = ?
                  AND usage_family = ?
                  AND purpose = ?
                """, Timestamp.class, "default", "qwen-plus", "CHAT", "analysis");
        assertThat(updatedAt).isNotNull();
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

    private boolean uniqueConstraintContainsColumn(String constraintName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.key_column_usage
                WHERE table_schema = 'public'
                  AND constraint_name = ?
                  AND column_name = ?
                """, Integer.class, constraintName, columnName);
        return count != null && count > 0;
    }

    private boolean hasIndex(String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = ?
                """, Integer.class, indexName);
        return count != null && count > 0;
    }

    private String columnType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject("""
                SELECT udt_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """, String.class, tableName, columnName);
    }

    private Long insertUser() {
        String username = "usage-user-" + UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, username, username + "@example.com", "hashed", "zh-CN", "Asia/Shanghai");
    }

    private void upsertUserCreditPeriod(
            Long userId,
            long resumeCredits,
            long kbQueryCredits,
            long kbIngestionCredits,
            long interviewCredits
    ) {
        jdbcTemplate.update("""
                INSERT INTO user_credit_period (
                    user_id,
                    period_type,
                    period_start,
                    period_end,
                    resume_credits_used_micros,
                    kb_query_credits_used_micros,
                    kb_ingestion_credits_used_micros,
                    interview_credits_used_micros
                )
                VALUES (?, 'MONTHLY', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, period_type, period_start, period_end)
                DO UPDATE SET
                    resume_credits_used_micros = user_credit_period.resume_credits_used_micros + EXCLUDED.resume_credits_used_micros,
                    kb_query_credits_used_micros = user_credit_period.kb_query_credits_used_micros + EXCLUDED.kb_query_credits_used_micros,
                    kb_ingestion_credits_used_micros = user_credit_period.kb_ingestion_credits_used_micros + EXCLUDED.kb_ingestion_credits_used_micros,
                    interview_credits_used_micros = user_credit_period.interview_credits_used_micros + EXCLUDED.interview_credits_used_micros,
                    updated_at = CURRENT_TIMESTAMP
                """,
                userId,
                Timestamp.valueOf(LocalDateTime.of(2026, 3, 1, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0)),
                resumeCredits,
                kbQueryCredits,
                kbIngestionCredits,
                interviewCredits
        );
    }

    private void upsertInternalPeriod(
            String provider,
            String modelCode,
            String usageFamily,
            String purpose,
            long totalRecordedTokens,
            long totalRecordedCachedTokens,
            long totalRequestCount,
            long totalChargeableTokens,
            long totalChargeableRequestCount,
            String totalBilledAmount
    ) {
        jdbcTemplate.update("""
                INSERT INTO llm_usage_internal_period (
                    period_type,
                    period_start,
                    period_end,
                    provider,
                    model_code,
                    usage_family,
                    purpose,
                    total_recorded_tokens,
                    total_recorded_cached_tokens,
                    total_request_count,
                    total_chargeable_tokens,
                    total_chargeable_request_count,
                    total_billed_amount,
                    currency
                )
                VALUES ('MONTHLY', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS numeric), 'USD')
                ON CONFLICT (period_type, period_start, period_end, provider, model_code, usage_family, purpose)
                DO UPDATE SET
                    total_recorded_tokens = llm_usage_internal_period.total_recorded_tokens + EXCLUDED.total_recorded_tokens,
                    total_recorded_cached_tokens = llm_usage_internal_period.total_recorded_cached_tokens + EXCLUDED.total_recorded_cached_tokens,
                    total_request_count = llm_usage_internal_period.total_request_count + EXCLUDED.total_request_count,
                    total_chargeable_tokens = llm_usage_internal_period.total_chargeable_tokens + EXCLUDED.total_chargeable_tokens,
                    total_chargeable_request_count = llm_usage_internal_period.total_chargeable_request_count + EXCLUDED.total_chargeable_request_count,
                    total_billed_amount = llm_usage_internal_period.total_billed_amount + EXCLUDED.total_billed_amount,
                    updated_at = CURRENT_TIMESTAMP
                """,
                Timestamp.valueOf(LocalDateTime.of(2026, 3, 1, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0)),
                provider,
                modelCode,
                usageFamily,
                purpose,
                totalRecordedTokens,
                totalRecordedCachedTokens,
                totalRequestCount,
                totalChargeableTokens,
                totalChargeableRequestCount,
                totalBilledAmount
        );
    }
}
