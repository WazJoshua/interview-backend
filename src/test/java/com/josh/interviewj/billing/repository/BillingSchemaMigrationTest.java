package com.josh.interviewj.billing.repository;

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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillingSchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("billing_schema_migration_test")
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
    void migrateToLatest_CreatesBillingCoreTablesAndConstraints() {
        migrateToLatest();

        assertThat(tableExists("billing_plan")).isTrue();
        assertThat(tableExists("billing_plan_version")).isTrue();
        assertThat(tableExists("billing_plan_entitlement_item")).isTrue();
        assertThat(tableExists("credit_purchase_sku")).isTrue();
        assertThat(tableExists("credit_purchase_sku_version")).isTrue();
        assertThat(tableExists("payment_order")).isTrue();
        assertThat(tableExists("payment_event")).isTrue();
        assertThat(tableExists("billing_event")).isTrue();
        assertThat(tableExists("subscription_contract")).isTrue();
        assertThat(tableExists("subscription_quota_grant")).isTrue();
        assertThat(tableExists("credit_lot")).isTrue();
        assertThat(tableExists("credit_wallet")).isTrue();
        assertThat(tableExists("billing_reconciliation_case")).isTrue();

        assertThat(hasConstraint("payment_order", "uq_payment_order_order_no", "UNIQUE")).isTrue();
        assertThat(hasConstraint("payment_order", "uq_payment_order_user_idempotency_key", "UNIQUE")).isTrue();
        assertThat(hasConstraint("payment_order", "uq_payment_order_idempotency_key", "UNIQUE")).isFalse();
        assertThat(hasConstraint("payment_order", "uq_payment_order_renewal_window", "UNIQUE")).isTrue();
        assertThat(hasConstraint("payment_event", "uq_payment_event_provider_event", "UNIQUE")).isTrue();
        assertThat(hasConstraint("billing_event", "uq_billing_event_idempotency_key", "UNIQUE")).isTrue();
        assertThat(hasConstraint("credit_lot", "uq_credit_lot_source_billing_event_id", "UNIQUE")).isTrue();
        assertThat(hasConstraint("subscription_quota_grant", "uq_subscription_quota_grant_scope", "UNIQUE")).isTrue();

        assertThat(uniqueConstraintContainsColumn("uq_payment_order_user_idempotency_key", "user_id")).isTrue();
        assertThat(uniqueConstraintContainsColumn("uq_payment_order_user_idempotency_key", "idempotency_key")).isTrue();
        assertThat(uniqueConstraintContainsColumn("uq_payment_order_renewal_window", "subscription_contract_id")).isTrue();
        assertThat(uniqueConstraintContainsColumn("uq_payment_order_renewal_window", "renewal_period_start")).isTrue();
        assertThat(uniqueConstraintContainsColumn("uq_payment_order_renewal_window", "renewal_period_end")).isTrue();
        assertThat(uniqueConstraintContainsColumn("uq_payment_order_renewal_window", "order_type")).isTrue();
        assertThat(partialUniqueIndexExists(
                "idx_subscription_contract_open_unique",
                "CREATE UNIQUE INDEX idx_subscription_contract_open_unique ON public.subscription_contract USING btree (user_id) WHERE ((status)::text = ANY ((ARRAY['PENDING_ACTIVATION'::character varying, 'ACTIVE'::character varying, 'PAST_DUE'::character varying])::text[]))"
        )).isTrue();
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

    private boolean partialUniqueIndexExists(String indexName, String expectedDefinition) {
        String indexDefinition = jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = ?
                """, String.class, indexName);
        return expectedDefinition.equals(indexDefinition);
    }
}
