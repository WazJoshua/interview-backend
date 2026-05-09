package com.josh.interviewj.admin;

import com.josh.interviewj.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class AdminManagementIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Long adminUserId;
    private String adminUsername;
    private String memberUsername;
    private UUID memberExternalId;

    @BeforeEach
    void setUp() {
        adminUsername = "admin-" + UUID.randomUUID();
        adminUserId = jdbcTemplate.queryForObject("""
                INSERT INTO users (external_id, username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, UUID.randomUUID(), adminUsername, adminUsername + "@example.com", "hashed", "zh-CN", "Asia/Shanghai");
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", adminUserId, "ADMIN");

        memberUsername = "member-" + UUID.randomUUID();
        memberExternalId = jdbcTemplate.queryForObject("""
                INSERT INTO users (external_id, username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING external_id
                """, UUID.class, UUID.randomUUID(), memberUsername, memberUsername + "@example.com", "hashed", "zh-CN", "Asia/Shanghai");
        Long memberId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE external_id = ?", Long.class, memberExternalId);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", memberId, "USER");

        Long providerId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_provider (
                    provider_key, display_name, base_url, enabled,
                    default_timeout_ms, default_max_retries, supported_usage_families, metadata
                ) VALUES (?, ?, ?, TRUE, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                ON CONFLICT (provider_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    base_url = EXCLUDED.base_url,
                    enabled = EXCLUDED.enabled,
                    default_timeout_ms = EXCLUDED.default_timeout_ms,
                    default_max_retries = EXCLUDED.default_max_retries,
                    supported_usage_families = EXCLUDED.supported_usage_families,
                    metadata = EXCLUDED.metadata,
                    deleted_at = NULL
                RETURNING id
                """,
                Long.class,
                "primary_chat",
                "Primary Chat",
                "https://provider.example.com/v1",
                30000,
                3,
                "[\"CHAT\"]",
                "{\"seed\":\"AdminManagementIntegrationTest\"}");
        jdbcTemplate.update("""
                INSERT INTO llm_provider_secret (
                    provider_id, api_key_ciphertext, api_key_masked, encryption_key_version, encryption_type
                ) VALUES (?, ?, ?, 'current', 'AES_GCM')
                ON CONFLICT (provider_id) DO UPDATE SET
                    api_key_ciphertext = EXCLUDED.api_key_ciphertext,
                    api_key_masked = EXCLUDED.api_key_masked,
                    encryption_key_version = EXCLUDED.encryption_key_version,
                    encryption_type = EXCLUDED.encryption_type,
                    updated_at = CURRENT_TIMESTAMP
                """,
                providerId,
                "seed-ciphertext",
                "pr****ey");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminBatch_CreateModelPricingAndInviter_WritesAuditLog() throws Exception {
        String modelCode = "admin-model-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/llm/models")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "primary_chat",
                                "modelCode", modelCode,
                                "usageFamily", "CHAT",
                                "displayName", "Admin Managed Model",
                                "active", true,
                                "metadata", Map.of("channel", "admin")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelCode").value(modelCode));

        mockMvc.perform(post("/api/v1/admin/llm/pricing-versions")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", "primary_chat",
                                "modelCode", modelCode,
                                "usageFamily", "CHAT",
                                "effectiveFrom", "2026-05-01T00:00:00Z",
                                "billingUnit", "TOKEN",
                                "promptTokenPrice", "0.000010",
                                "currency", "USD",
                                "metadata", Map.of("channel", "admin")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelCode").value(modelCode));

        mockMvc.perform(put("/api/v1/admin/users/{userId}/role-flags", memberExternalId)
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("inviter", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flags.inviter").value(true));

        mockMvc.perform(get("/api/v1/admin/users/{userId}/role-flags", memberExternalId)
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flags.inviter").value(true));

        mockMvc.perform(get("/api/v1/admin/llm/models")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .param("provider", "primary_chat")
                        .param("usageFamily", "CHAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].modelCode").value(modelCode));

        mockMvc.perform(get("/api/v1/admin/llm/pricing-versions")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .param("provider", "primary_chat")
                        .param("modelCode", modelCode)
                        .param("usageFamily", "CHAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].modelCode").value(modelCode));

        Integer totalLogs = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_operation_log WHERE actor_user_id = ?", Integer.class, adminUserId);
        assertThat(totalLogs).isEqualTo(3);

        Integer modelLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_operation_log WHERE actor_user_id = ? AND resource_type = 'LLM_MODEL_CATALOG'",
                Integer.class,
                adminUserId
        );
        Integer pricingLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_operation_log WHERE actor_user_id = ? AND resource_type = 'LLM_PRICING_VERSION'",
                Integer.class,
                adminUserId
        );
        Integer roleLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_operation_log WHERE actor_user_id = ? AND resource_type = 'USER_ROLE_FLAGS'",
                Integer.class,
                adminUserId
        );
        assertThat(modelLogs).isEqualTo(1);
        assertThat(pricingLogs).isEqualTo(1);
        assertThat(roleLogs).isEqualTo(1);
    }

    @Test
    void nonAdminAccess_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/llm/providers")
                        .principal(authenticatedPrincipal(memberUsername, "ROLE_USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("AUTH_006"));
    }

    @Test
    void adminCanInspectHealthAfterDatabaseTruthConfigured() throws Exception {
        mockMvc.perform(get("/api/v1/admin/llm/providers")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sourceOfTruth").value("DATABASE"));

        mockMvc.perform(get("/api/v1/admin/llm/health")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.databaseVersion").isNumber())
                .andExpect(jsonPath("$.data.secretKeyVersionStats.current").isNumber());
    }

    @Test
    void adminCanManageRerankProviderModelAndRoutingMetadata() throws Exception {
        String providerKey = "rerank-provider-" + UUID.randomUUID();
        String modelCode = "qwen-rerank-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/llm/providers")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", providerKey,
                                "displayName", "Rerank Provider",
                                "baseUrl", "https://rerank.example.com/v1",
                                "enabled", true,
                                "supportedUsageFamilies", List.of("RERANK"),
                                "apiKey", "rerank-secret"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value(providerKey));

        mockMvc.perform(post("/api/v1/admin/llm/models")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", providerKey,
                                "modelCode", modelCode,
                                "usageFamily", "RERANK",
                                "displayName", "Managed Rerank Model",
                                "active", true,
                                "metadata", Map.of()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usageFamily").value("RERANK"));

        mockMvc.perform(put("/api/v1/admin/llm/routing-policies/{purpose}", "kb_query_rerank")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", providerKey,
                                "modelCode", modelCode,
                                "usageFamily", "RERANK",
                                "enabled", true,
                                "timeoutMs", 3000,
                                "maxRetries", 1,
                                "metadata", Map.of(
                                        "preRerankCandidateCap", 24,
                                        "stage1TopN", 10,
                                        "stage1RelevanceThreshold", 0.15,
                                        "dualQueryEnabled", true
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usageFamily").value("RERANK"))
                .andExpect(jsonPath("$.data.metadata.preRerankCandidateCap").value(24))
                .andExpect(jsonPath("$.data.metadata.dualQueryEnabled").value(true));
    }

    @Test
    void adminCanUpdateRoutingPolicyWithoutClearingExistingMetadata() throws Exception {
        String providerKey = "rerank-provider-" + UUID.randomUUID();
        String modelCode = "qwen-rerank-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/llm/providers")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", providerKey,
                                "displayName", "Rerank Provider",
                                "baseUrl", "https://rerank.example.com/v1",
                                "enabled", true,
                                "supportedUsageFamilies", List.of("RERANK"),
                                "apiKey", "rerank-secret"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/llm/models")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", providerKey,
                                "modelCode", modelCode,
                                "usageFamily", "RERANK",
                                "displayName", "Managed Rerank Model",
                                "active", true,
                                "metadata", Map.of()
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/llm/routing-policies/{purpose}", "kb_query_rerank")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "provider", providerKey,
                                "modelCode", modelCode,
                                "usageFamily", "RERANK",
                                "enabled", true,
                                "timeoutMs", 3000,
                                "maxRetries", 1,
                                "metadata", Map.of(
                                        "preRerankCandidateCap", 24,
                                        "stage1TopN", 10,
                                        "stage1RelevanceThreshold", 0.15,
                                        "dualQueryEnabled", true
                                )
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/llm/routing-policies/{purpose}", "kb_query_rerank")
                        .principal(authenticatedPrincipal(adminUsername, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "%s",
                                  "modelCode": "%s",
                                  "usageFamily": "RERANK",
                                  "enabled": true,
                                  "timeoutMs": 4500,
                                  "maxRetries": 2
                                }
                                """.formatted(providerKey, modelCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timeoutMs").value(4500))
                .andExpect(jsonPath("$.data.metadata.preRerankCandidateCap").value(24))
                .andExpect(jsonPath("$.data.metadata.stage1TopN").value(10))
                .andExpect(jsonPath("$.data.metadata.dualQueryEnabled").value(true));
    }

    private UsernamePasswordAuthenticationToken authenticatedPrincipal(String username, String authority) {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                username,
                "n/a",
                List.of(new SimpleGrantedAuthority(authority))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }
}
