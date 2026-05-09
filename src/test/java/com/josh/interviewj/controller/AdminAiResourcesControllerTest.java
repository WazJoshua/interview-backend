package com.josh.interviewj.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.usage.controller.AdminAiResourcesController;
import com.josh.interviewj.usage.dto.response.AdminModelCatalogResponse;
import com.josh.interviewj.usage.dto.response.AdminPricingVersionResponse;
import com.josh.interviewj.usage.dto.response.AdminProviderDetailResponse;
import com.josh.interviewj.usage.dto.response.AdminProviderOptionResponse;
import com.josh.interviewj.usage.dto.response.AdminRoutingPolicyResponse;
import com.josh.interviewj.usage.dto.response.LlmHealthCheckResponse;
import com.josh.interviewj.usage.service.AdminAiResourcesService;
import com.josh.interviewj.usage.service.LlmProviderHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminAiResourcesControllerTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private AdminAiResourcesService adminAiResourcesService;

    @Mock
    private LlmProviderHealthService llmProviderHealthService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminAiResourcesController(
                        adminAccessService,
                        adminAiResourcesService,
                        llmProviderHealthService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getProviders_NonAdmin_Returns403() throws Exception {
        doThrow(new BusinessException(ErrorCode.AUTH_006, "Forbidden"))
                .when(adminAccessService).requireAdmin(any());

        mockMvc.perform(get("/api/v1/admin/llm/providers")
                        .principal(new UsernamePasswordAuthenticationToken("user", "n/a")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.AUTH_006));
    }

    @Test
    void getProviders_ReturnsControlledOptions() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminAiResourcesService.getProviders()).thenReturn(List.of(
                AdminProviderOptionResponse.builder()
                        .id("1")
                        .provider("dispatcher_rc")
                        .displayName("Dispatcher RC")
                        .enabled(true)
                        .supportedUsageFamilies(List.of("CHAT", "EMBEDDING"))
                        .sourceOfTruth("DATABASE")
                        .build()
        ));

        mockMvc.perform(get("/api/v1/admin/llm/providers")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].provider").value("dispatcher_rc"))
                .andExpect(jsonPath("$.data[0].supportedUsageFamilies[0]").value("CHAT"))
                .andExpect(jsonPath("$.data[0].sourceOfTruth").value("DATABASE"));
    }

    @Test
    void createProvider_ReturnsCreatedProvider() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminAiResourcesService.createProvider(anyLong(), any())).thenReturn(
                AdminProviderDetailResponse.builder()
                        .id("1")
                        .provider("db-provider")
                        .displayName("DB Provider")
                        .enabled(true)
                        .apiKeyMasked("db-****ey")
                        .sourceOfTruth("DATABASE")
                        .build()
        );

        mockMvc.perform(post("/api/v1/admin/llm/providers")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "db-provider",
                                  "displayName": "DB Provider",
                                  "baseUrl": "https://db.example.com/v1",
                                  "enabled": true,
                                  "supportedUsageFamilies": ["CHAT"],
                                  "apiKey": "db-secret-key"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("db-provider"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("db-****ey"));
    }

    @Test
    void getModels_ReturnsPagedModels() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminAiResourcesService.getModels(any())).thenReturn(new PageImpl<>(List.of(
                AdminModelCatalogResponse.builder()
                        .id("1")
                        .provider("dispatcher_rc")
                        .modelCode("gpt-5.4")
                        .usageFamily("CHAT")
                        .displayName("GPT-5.4")
                        .active(true)
                        .metadata(Map.of())
                        .build()
        ), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/llm/models")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value("1"))
                .andExpect(jsonPath("$.data.content[0].active").value(true));
    }

    @Test
    void createModel_ReturnsCreatedModel() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminAiResourcesService.createModel(anyLong(), any())).thenReturn(
                AdminModelCatalogResponse.builder()
                        .id("1")
                        .provider("dispatcher_rc")
                        .modelCode("gpt-5.4")
                        .usageFamily("CHAT")
                        .displayName("GPT-5.4")
                        .active(true)
                        .metadata(Map.of("tier", "default"))
                        .createdAt(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                        .updatedAt(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                        .build()
        );

        mockMvc.perform(post("/api/v1/admin/llm/models")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "dispatcher_rc",
                                  "modelCode": "gpt-5.4",
                                  "usageFamily": "CHAT",
                                  "displayName": "GPT-5.4",
                                  "active": true,
                                  "metadata": {
                                    "tier": "default"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("1"))
                .andExpect(jsonPath("$.data.provider").value("dispatcher_rc"));
    }

    @Test
    void updateModel_ReturnsUpdatedModel() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminAiResourcesService.updateModel(anyLong(), any(), any())).thenReturn(
                AdminModelCatalogResponse.builder()
                        .id("1")
                        .provider("dispatcher_rc")
                        .modelCode("gpt-5.4")
                        .usageFamily("CHAT")
                        .displayName("GPT-5.4 Turbo")
                        .active(false)
                        .metadata(Map.of("tier", "beta"))
                        .build()
        );

        mockMvc.perform(put("/api/v1/admin/llm/models/{id}", 1)
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "GPT-5.4 Turbo",
                                  "active": false,
                                  "metadata": {
                                    "tier": "beta"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("GPT-5.4 Turbo"))
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    void getPricingVersions_ReturnsPagedVersions() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminAiResourcesService.getPricingVersions(any())).thenReturn(new PageImpl<>(List.of(
                AdminPricingVersionResponse.builder()
                        .id("10")
                        .provider("dispatcher_rc")
                        .modelCode("gpt-5.4")
                        .usageFamily("CHAT")
                        .billingUnit("TOKEN")
                        .promptTokenPrice("0.000010")
                        .currency("USD")
                        .metadata(Map.of())
                        .build()
        ), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/llm/pricing-versions")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value("10"))
                .andExpect(jsonPath("$.data.content[0].billingUnit").value("TOKEN"));
    }

    @Test
    void createPricingVersion_InvalidCombination_Returns422() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminAiResourcesService.createPricingVersion(anyLong(), any()))
                .thenThrow(new BusinessException(ErrorCode.ADMIN_LLM_001, "TOKEN billing requires at least one token price"));

        mockMvc.perform(post("/api/v1/admin/llm/pricing-versions")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "dispatcher_rc",
                                  "modelCode": "gpt-5.4",
                                  "usageFamily": "CHAT",
                                  "effectiveFrom": "2026-05-01T00:00:00Z",
                                  "billingUnit": "TOKEN",
                                  "currency": "USD"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.ADMIN_LLM_001));
    }

    @Test
    void getRoutingPolicies_ReturnsDatabasePolicies() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminAiResourcesService.getRoutingPolicies()).thenReturn(List.of(
                AdminRoutingPolicyResponse.builder()
                        .id("41")
                        .purpose("analysis")
                        .usageFamily("CHAT")
                        .provider("dispatcher_rc")
                        .modelCode("gpt-5.4")
                        .strategy("single")
                        .timeoutMs(1500)
                        .maxRetries(1)
                        .fallback(List.of())
                        .sourceOfTruth("DATABASE")
                        .editable(true)
                        .build()
        ));

        mockMvc.perform(get("/api/v1/admin/llm/routing-policies")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("41"))
                .andExpect(jsonPath("$.data[0].sourceOfTruth").value("DATABASE"))
                .andExpect(jsonPath("$.data[0].editable").value(true));
    }

    @Test
    void updateRoutingPolicy_RerankMetadataRoundTrips() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminAiResourcesService.updateRoutingPolicy(anyLong(), any(), any())).thenReturn(
                AdminRoutingPolicyResponse.builder()
                        .id("51")
                        .purpose("kb_query_rerank")
                        .usageFamily("RERANK")
                        .provider("rerank_provider")
                        .modelCode("qwen-rerank")
                        .strategy("single")
                        .timeoutMs(3000)
                        .maxRetries(1)
                        .metadata(Map.of(
                                "preRerankCandidateCap", 24,
                                "stage1TopN", 10,
                                "stage1RelevanceThreshold", 0.15,
                                "dualQueryEnabled", true
                        ))
                        .fallback(List.of())
                        .sourceOfTruth("DATABASE")
                        .editable(true)
                        .build()
        );

        mockMvc.perform(put("/api/v1/admin/llm/routing-policies/{purpose}", "kb_query_rerank")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "rerank_provider",
                                  "modelCode": "qwen-rerank",
                                  "usageFamily": "RERANK",
                                  "enabled": true,
                                  "timeoutMs": 3000,
                                  "maxRetries": 1,
                                  "metadata": {
                                    "preRerankCandidateCap": 24,
                                    "stage1TopN": 10,
                                    "stage1RelevanceThreshold": 0.15,
                                    "dualQueryEnabled": true
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usageFamily").value("RERANK"))
                .andExpect(jsonPath("$.data.metadata.preRerankCandidateCap").value(24))
                .andExpect(jsonPath("$.data.metadata.dualQueryEnabled").value(true));
    }

    @Test
    void getHealth_ReturnsHealthStatus() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(llmProviderHealthService.health()).thenReturn(
                LlmHealthCheckResponse.builder()
                        .healthy(true)
                        .databaseVersion(3L)
                        .cachedVersion(3L)
                        .providerCount(2)
                        .routingCount(2)
                        .secretKeyVersionStats(Map.of("current", 2L))
                        .issues(List.of())
                        .build()
        );

        mockMvc.perform(get("/api/v1/admin/llm/health")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.healthy").value(true))
                .andExpect(jsonPath("$.data.databaseVersion").value(3));
    }

    private User adminUser() {
        return User.builder()
                .id(1L)
                .externalId(UUID.randomUUID())
                .username("admin")
                .email("admin@example.com")
                .password("hashed")
                .build();
    }
}
