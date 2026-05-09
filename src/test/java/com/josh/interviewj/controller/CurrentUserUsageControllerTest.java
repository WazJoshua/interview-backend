package com.josh.interviewj.controller;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.usage.dto.response.UserUsageHistoryItemResponse;
import com.josh.interviewj.usage.dto.response.UserUsageOverviewResponse;
import com.josh.interviewj.usage.model.UsageEntryType;
import com.josh.interviewj.usage.model.UsageHistoryCategory;
import com.josh.interviewj.usage.model.UsageHistorySourceType;
import com.josh.interviewj.usage.model.UsageHistoryWindowType;
import com.josh.interviewj.usage.service.UserUsageHistoryQueryService;
import com.josh.interviewj.usage.service.UserUsageOverviewQueryService;
import com.josh.interviewj.user.controller.CurrentUserUsageController;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CurrentUserUsageControllerTest {

    @Mock
    private UserUsageOverviewQueryService userUsageOverviewQueryService;

    @Mock
    private UserUsageHistoryQueryService userUsageHistoryQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new CurrentUserUsageController(
                        userUsageOverviewQueryService,
                        userUsageHistoryQueryService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void getUsageOverview_WhenNoCredits_Returns200AndFixedSubscriptionObject() throws Exception {
        when(userUsageOverviewQueryService.getCurrentUserOverview("josh")).thenReturn(
                UserUsageOverviewResponse.builder()
                        .snapshotAt(OffsetDateTime.of(2026, 4, 9, 9, 0, 0, 0, ZoneOffset.UTC))
                        .defaultWindow(UserUsageOverviewResponse.DefaultWindow.builder()
                                .windowType(UsageHistoryWindowType.LAST_30_DAYS)
                                .from(OffsetDateTime.of(2026, 3, 10, 0, 0, 0, 0, ZoneOffset.UTC))
                                .to(OffsetDateTime.of(2026, 4, 9, 0, 0, 0, 0, ZoneOffset.UTC))
                                .build())
                        .balance(UserUsageOverviewResponse.Balance.builder()
                                .totalCreditsMicros(0L)
                                .totalCredits("0.000")
                                .spendableCreditsMicros(0L)
                                .spendableCredits("0.000")
                                .purchasedAvailableCreditsMicros(0L)
                                .purchasedAvailableCredits("0.000")
                                .purchasedTotalCreditsMicros(0L)
                                .purchasedTotalCredits("0.000")
                                .purchasedUsedCreditsMicros(0L)
                                .purchasedUsedCredits("0.000")
                                .rawPurchasedBalanceMicros(0L)
                                .rawPurchasedBalance("0.000")
                                .subscriptionAvailableCreditsMicros(0L)
                                .subscriptionAvailableCredits("0.000")
                                .hasAnyCredits(false)
                                .isNegative(false)
                                .build())
                        .subscription(UserUsageOverviewResponse.Subscription.builder()
                                .active(false)
                                .status(null)
                                .planCode(null)
                                .tierCode(null)
                                .currentBillingCycle(null)
                                .currentPeriodStart(null)
                                .currentPeriodEnd(null)
                                .bucketCount(0)
                                .buckets(List.of())
                                .build())
                        .build());

        mockMvc.perform(get("/api/v1/users/me/usage-overview")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance.totalCredits").value("0.000"))
                .andExpect(jsonPath("$.data.balance.purchasedTotalCredits").value("0.000"))
                .andExpect(jsonPath("$.data.balance.purchasedUsedCredits").value("0.000"))
                .andExpect(jsonPath("$.data.subscription.active").value(false))
                .andExpect(jsonPath("$.data.subscription.bucketCount").value(0))
                .andExpect(jsonPath("$.data.subscription.buckets.length()").value(0))
                .andExpect(jsonPath("$.timestamp", Matchers.matchesPattern(".*Z$")));
    }

    @Test
    void getUsageOverview_WhenUserMissing_Returns401() throws Exception {
        when(userUsageOverviewQueryService.getCurrentUserOverview("josh"))
                .thenThrow(new BusinessException("AUTH_003", "User not found"));

        mockMvc.perform(get("/api/v1/users/me/usage-overview")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("AUTH_003"))
                .andExpect(jsonPath("$.timestamp", Matchers.matchesPattern(".*Z$")));
    }

    @Test
    void getUsageHistory_ReturnsPageEnvelope() throws Exception {
        when(userUsageHistoryQueryService.getCurrentUserHistory(eq("josh"), any())).thenReturn(new PageImpl<>(List.of(
                UserUsageHistoryItemResponse.builder()
                        .id("usage-evt-1")
                        .entryType(UsageEntryType.USAGE)
                        .category(UsageHistoryCategory.KB_QUERY)
                        .categoryLabel("Knowledge Base Query")
                        .occurredAt(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                        .creditsDeltaMicros(-1500L)
                        .creditsDelta("-1.500")
                        .sourceType(UsageHistorySourceType.SUBSCRIPTION)
                        .usage(UserUsageHistoryItemResponse.UsageDetails.builder()
                                .usageFamily("CHAT")
                                .chargeBucket("KB_QUERY_CREDITS")
                                .resourceType("KNOWLEDGE_BASE_QUERY")
                                .resourceExternalId("kb-1")
                                .operationId("op-1")
                                .build())
                        .build()
        ), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/users/me/usage-history")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].entryType").value("USAGE"))
                .andExpect(jsonPath("$.data.content[0].category").value("KB_QUERY"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.pageable.pageNumber").value(0));
    }

    @Test
    void getUsageHistory_WhenCustomWindowMissingFromTo_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/usage-history")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a"))
                        .queryParam("windowType", "CUSTOM")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
    }

    @Test
    void getUsageHistory_WhenNonCustomWindowIncludesFromTo_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/usage-history")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a"))
                        .queryParam("windowType", "LAST_30_DAYS")
                        .queryParam("from", "2026-04-01T00:00:00Z")
                        .queryParam("to", "2026-04-02T00:00:00Z")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
    }
}
