package com.josh.interviewj.controller;

import com.josh.interviewj.usage.support.UsageIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class AdminUsageSummaryIntegrationTest extends UsageIntegrationTestBase {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRESQL = POSTGRES;

    @Container
    static final com.redis.testcontainers.RedisContainer REDIS_CONTAINER = REDIS;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String adminUsername;
    private Long staleProviderId;
    private Long canonicalProviderId;
    private Long canonicalModelId;

    @BeforeEach
    void setUp() {
        adminUsername = "admin-" + UUID.randomUUID();
        Long adminId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, adminUsername, adminUsername + "@example.com", "hashed", "zh-CN", "Asia/Shanghai");
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", adminId, "ADMIN");

        staleProviderId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_provider (
                    provider_key, display_name, enabled, supported_usage_families, metadata
                ) VALUES (?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (provider_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    enabled = EXCLUDED.enabled,
                    supported_usage_families = EXCLUDED.supported_usage_families,
                    metadata = EXCLUDED.metadata,
                    deleted_at = NULL
                RETURNING id
                """, Long.class, "stale_provider", "Stale Provider", true, "[\"CHAT\"]", "{}");
        canonicalProviderId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_provider (
                    provider_key, display_name, enabled, supported_usage_families, metadata
                ) VALUES (?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (provider_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    enabled = EXCLUDED.enabled,
                    supported_usage_families = EXCLUDED.supported_usage_families,
                    metadata = EXCLUDED.metadata,
                    deleted_at = NULL
                RETURNING id
                """, Long.class, "dispatcher_rc", "Dispatcher RC", true, "[\"CHAT\"]", "{}");
        canonicalModelId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_model_catalog (
                    provider, provider_id, model_code, usage_family, display_name, active, metadata
                ) VALUES (?, ?, ?, 'CHAT', ?, ?, ?::jsonb)
                ON CONFLICT (provider, model_code, usage_family) DO UPDATE SET
                    provider_id = EXCLUDED.provider_id,
                    display_name = EXCLUDED.display_name,
                    active = EXCLUDED.active,
                    metadata = EXCLUDED.metadata
                RETURNING id
                """, Long.class, "legacy-provider", canonicalProviderId, "gpt-5.4", "GPT-5.4", true, "{}");

        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, "usage-" + UUID.randomUUID(), "usage-" + UUID.randomUUID() + "@example.com", "hashed", "zh-CN", "Asia/Shanghai");
        LocalDateTime occurredAt = LocalDateTime.of(2026, 4, 1, 0, 0);
        Long eventId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_usage_event (
                    user_id, usage_family, purpose, provider, provider_id, model_code, model_id,
                    resource_type, resource_external_id, operation_id,
                    request_count, prompt_tokens, completion_tokens, total_tokens,
                    charge_bucket, business_outcome, dedupe_key, occurred_at, created_at
                ) VALUES (?, 'CHAT', 'analysis', 'legacy-provider', ?, 'legacy-model', ?,
                          'RESUME_ANALYSIS_REPORT', ?, ?, 1, 100, 20, 120,
                          'RESUME_CREDITS', 'SUCCESS', ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId,
                staleProviderId,
                canonicalModelId,
                UUID.randomUUID().toString(),
                "op-1",
                "dedupe-" + UUID.randomUUID(),
                Timestamp.valueOf(occurredAt),
                Timestamp.valueOf(occurredAt)
        );
        jdbcTemplate.update("""
                INSERT INTO llm_usage_charge_ledger (
                    usage_event_id, prompt_token_units, completion_token_units, cached_token_units,
                    request_units, prompt_amount, completion_amount, cached_amount, request_amount,
                    total_amount, currency, charge_status
                ) VALUES (?, 100, 20, 0, 1, 1.0, 0.2, 0, 0.1, 1.3, 'USD', 'CHARGEABLE')
                """, eventId);
        jdbcTemplate.update("""
                INSERT INTO llm_usage_credit_ledger (
                    usage_event_id, charge_bucket, charged_credits_micros, charge_status
                ) VALUES (?, 'RESUME_CREDITS', 1500, 'CHARGEABLE')
                """, eventId);
    }

    @Test
    void usageSummary_ReturnsAggregates() throws Exception {
        mockMvc.perform(get("/api/v1/admin/usage-summary")
                        .principal(new UsernamePasswordAuthenticationToken(
                                adminUsername,
                                "n/a",
                                AuthorityUtils.createAuthorityList("ROLE_ADMIN")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall.totalRecordedTokens").value(120))
                .andExpect(jsonPath("$.data.rows[0].provider").value("dispatcher_rc"))
                .andExpect(jsonPath("$.data.rows[0].modelCode").value("gpt-5.4"))
                .andExpect(jsonPath("$.data.rows[0].totalChargedCreditsMicros").value(1500));
    }

    @Test
    void usageEvents_WhenOptionalFiltersMissing_ReturnsRows() throws Exception {
        mockMvc.perform(get("/api/v1/admin/usage-events")
                        .principal(new UsernamePasswordAuthenticationToken(
                                adminUsername,
                                "n/a",
                                AuthorityUtils.createAuthorityList("ROLE_ADMIN")
                        ))
                        .param("from", "2026-04-01T00:00:00Z")
                        .param("to", "2026-04-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").isNotEmpty())
                .andExpect(jsonPath("$.data.content[0].purpose").value("analysis"))
                .andExpect(jsonPath("$.data.content[0].provider").value("dispatcher_rc"))
                .andExpect(jsonPath("$.data.content[0].modelCode").value("gpt-5.4"))
                .andExpect(jsonPath("$.data.content[0].chargeBucket").value("RESUME_CREDITS"));
    }
}
