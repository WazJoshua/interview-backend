package com.josh.interviewj.admin;

import com.josh.interviewj.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LlmRerankSchemaIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM llm_routing_policy");
        jdbcTemplate.execute("DELETE FROM llm_model_pricing_version");
        jdbcTemplate.execute("DELETE FROM llm_model_catalog");
        jdbcTemplate.execute("DELETE FROM llm_provider_secret");
        jdbcTemplate.execute("DELETE FROM llm_provider");
    }

    @Test
    void existingSchema_AllowsRerankProviderModelAndRoutingMetadata() throws Exception {
        Long providerId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_provider (
                    provider_key,
                    display_name,
                    base_url,
                    enabled,
                    default_timeout_ms,
                    default_max_retries,
                    supported_usage_families
                )
                VALUES (?, ?, ?, TRUE, ?, ?, CAST(? AS jsonb))
                RETURNING id
                """, Long.class,
                "rerank-provider",
                "Rerank Provider",
                "https://rerank.example.com/v1",
                3_000,
                2,
                "[\"RERANK\"]"
        );
        Long modelId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_model_catalog (
                    provider,
                    provider_id,
                    model_code,
                    usage_family,
                    display_name,
                    active,
                    metadata
                )
                VALUES (?, ?, ?, 'RERANK', ?, TRUE, CAST(? AS jsonb))
                RETURNING id
                """, Long.class,
                "rerank-provider",
                providerId,
                "qwen-rerank",
                "Qwen Rerank",
                "{}"
        );
        Long routingId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_routing_policy (
                    purpose,
                    model_id,
                    enabled,
                    timeout_ms,
                    metadata
                )
                VALUES (?, ?, TRUE, ?, CAST(? AS jsonb))
                RETURNING id
                """, Long.class,
                "kb_query_rerank",
                modelId,
                4_000,
                """
                        {
                          "preRerankCandidateCap": 24,
                          "stage1TopN": 10,
                          "stage1RelevanceThreshold": 0.15,
                          "dualQueryEnabled": true
                        }
                        """
        );

        assertThat(providerId).isNotNull();
        assertThat(modelId).isNotNull();
        assertThat(routingId).isNotNull();

        String supportedUsageFamilies = jdbcTemplate.queryForObject(
                "SELECT supported_usage_families::text FROM llm_provider WHERE id = ?",
                String.class,
                providerId
        );
        String usageFamily = jdbcTemplate.queryForObject(
                "SELECT usage_family FROM llm_model_catalog WHERE id = ?",
                String.class,
                modelId
        );
        String metadata = jdbcTemplate.queryForObject(
                "SELECT metadata::text FROM llm_routing_policy WHERE id = ?",
                String.class,
                routingId
        );

        JsonNode metadataNode = objectMapper.readTree(metadata);
        assertThat(supportedUsageFamilies).contains("RERANK");
        assertThat(usageFamily).isEqualTo("RERANK");
        assertThat(metadataNode.path("preRerankCandidateCap").asInt()).isEqualTo(24);
        assertThat(metadataNode.path("stage1TopN").asInt()).isEqualTo(10);
        assertThat(metadataNode.path("stage1RelevanceThreshold").asDouble()).isEqualTo(0.15D);
        assertThat(metadataNode.path("dualQueryEnabled").asBoolean()).isTrue();
    }
}
