package com.josh.interviewj.admin;

import com.josh.interviewj.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LlmProviderSchemaIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void schemaContainsProviderRoutingVersionAndOutboxTables() {
        assertThat(tableExists("llm_provider")).isTrue();
        assertThat(tableExists("llm_provider_secret")).isTrue();
        assertThat(tableExists("llm_routing_policy")).isTrue();
        assertThat(tableExists("llm_config_version")).isTrue();
        assertThat(tableExists("llm_config_change_outbox")).isTrue();

        assertThat(hasColumn("llm_provider_secret", "provider_id")).isTrue();
        assertThat(hasColumn("llm_provider_secret", "api_key_ciphertext")).isTrue();
        assertThat(hasColumn("llm_provider_secret", "api_key_masked")).isTrue();
        assertThat(hasColumn("llm_provider_secret", "encryption_key_version")).isTrue();
        assertThat(hasColumn("llm_provider_secret", "encryption_type")).isTrue();

        assertThat(hasColumn("llm_routing_policy", "purpose")).isTrue();
        assertThat(hasColumn("llm_routing_policy", "model_id")).isTrue();
        assertThat(hasColumn("llm_routing_policy", "enabled")).isTrue();
        assertThat(hasColumn("llm_routing_policy", "timeout_ms")).isTrue();
        assertThat(hasColumn("llm_routing_policy", "max_retries")).isTrue();

        assertThat(hasConstraint("llm_provider", "uq_llm_provider_provider_key", "UNIQUE")).isTrue();
        assertThat(hasConstraint("llm_provider_secret", "fk_llm_provider_secret_provider", "FOREIGN KEY")).isTrue();
        assertThat(hasConstraint("llm_routing_policy", "uq_llm_routing_policy_purpose", "UNIQUE")).isTrue();
        assertThat(hasConstraint("llm_routing_policy", "fk_llm_routing_policy_model", "FOREIGN KEY")).isTrue();

        assertThat(hasColumn("llm_model_catalog", "provider_id")).isTrue();
        assertThat(hasColumn("llm_model_pricing_version", "model_id")).isTrue();
        assertThat(hasColumn("llm_usage_event", "model_id")).isTrue();
        assertThat(hasColumn("llm_usage_event", "provider_id")).isTrue();

        assertThat(hasConstraint("llm_model_catalog", "fk_llm_model_catalog_provider", "FOREIGN KEY")).isTrue();
        assertThat(hasConstraint("llm_model_pricing_version", "fk_llm_model_pricing_version_model", "FOREIGN KEY")).isTrue();
        assertThat(hasConstraint("llm_usage_event", "fk_llm_usage_event_model", "FOREIGN KEY")).isTrue();
        assertThat(hasConstraint("llm_usage_event", "fk_llm_usage_event_provider", "FOREIGN KEY")).isTrue();
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

    private boolean hasColumn(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
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
}
