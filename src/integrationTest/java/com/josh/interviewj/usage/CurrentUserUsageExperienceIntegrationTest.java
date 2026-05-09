package com.josh.interviewj.usage;

import com.josh.interviewj.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class CurrentUserUsageExperienceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void experience_NewUserWithoutCredits_ReturnsZeroOverviewAndEmptyHistory() throws Exception {
        String username = createUser("usage-new");

        mockMvc.perform(get("/api/v1/users/me/usage-overview")
                        .principal(userPrincipal(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance.totalCreditsMicros").value(0))
                .andExpect(jsonPath("$.data.balance.spendableCreditsMicros").value(0))
                .andExpect(jsonPath("$.data.subscription.active").value(false))
                .andExpect(jsonPath("$.data.subscription.bucketCount").value(0));

        mockMvc.perform(get("/api/v1/users/me/usage-history")
                        .principal(userPrincipal(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    void experience_PurchasedOnlyUser_ReturnsPurchasedOverviewWithoutSubscription() throws Exception {
        Long userId = createUserId("usage-purchased");
        jdbcTemplate.update("""
                INSERT INTO billing_event (
                    external_id, user_id, event_type, source_type, source_id, idempotency_key,
                    delta_amount_micros, bucket_code, occurred_at
                ) VALUES (?, ?, 'CREDIT_PURCHASE_GRANTED', 'TEST', ?, ?, ?, NULL, ?)
                """,
                UUID.randomUUID(),
                userId,
                "purchase-" + UUID.randomUUID(),
                "purchase-key-" + UUID.randomUUID(),
                180_000L,
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0))
        );
        Long usageEventId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_usage_event (
                    external_id, user_id, purpose, usage_family, provider, model_code,
                    resource_type, resource_external_id, operation_id, request_count,
                    total_tokens, charge_bucket, business_outcome, dedupe_key, occurred_at, created_at
                ) VALUES (?, ?, 'kb_query', 'CHAT', 'TEST', 'test-model',
                    'KNOWLEDGE_BASE_QUERY', ?, ?, 1, 1, 'KB_QUERY_CREDITS', 'SUCCESS', ?, ?, ?)
                RETURNING id
                """, Long.class,
                UUID.randomUUID(),
                userId,
                "kb-" + UUID.randomUUID(),
                "op-" + UUID.randomUUID(),
                "dedupe-" + UUID.randomUUID(),
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 2, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 2, 0, 0))
        );
        jdbcTemplate.update("""
                INSERT INTO llm_usage_credit_ledger (
                    usage_event_id, charge_bucket, charged_credits_micros, charge_status, metadata
                ) VALUES (?, 'KB_QUERY_CREDITS', ?, 'CHARGEABLE', CAST(? AS jsonb))
                """, usageEventId, 60_000L, "{\"subscriptionAllocatedMicros\":0,\"purchasedAllocatedMicros\":60000}");
        jdbcTemplate.update("""
                INSERT INTO credit_wallet (user_id, purchased_balance_micros)
                VALUES (?, ?)
                """, userId, 120_000L);
        String username = findUsername(userId);

                mockMvc.perform(get("/api/v1/users/me/usage-overview")
                        .principal(userPrincipal(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance.purchasedAvailableCreditsMicros").value(120000))
                .andExpect(jsonPath("$.data.balance.purchasedTotalCreditsMicros").value(180000))
                .andExpect(jsonPath("$.data.balance.purchasedUsedCreditsMicros").value(60000))
                .andExpect(jsonPath("$.data.balance.totalCreditsMicros").value(120000))
                .andExpect(jsonPath("$.data.subscription.active").value(false))
                .andExpect(jsonPath("$.data.defaultWindow.windowType").value("LAST_30_DAYS"));
    }

    @Test
    void experience_SubscriptionOnlyUser_ReturnsBucketsAndSubscriptionPeriodWindow() throws Exception {
        Long userId = createUserId("usage-subscription");
        Long billingPlanId = jdbcTemplate.queryForObject("""
                INSERT INTO billing_plan (plan_code, tier_code, display_name, active)
                VALUES (?, 'PRO', 'Pro Plan', TRUE)
                RETURNING id
                """, Long.class, "pro-monthly-" + UUID.randomUUID());
        Long billingPlanVersionId = jdbcTemplate.queryForObject("""
                INSERT INTO billing_plan_version (
                    billing_plan_id, version_no, billing_cycle, amount, currency,
                    sale_enabled, renewal_enabled, effective_from, effective_to
                ) VALUES (?, 1, 'MONTHLY', 99.000000, 'CNY', TRUE, TRUE, ?, NULL)
                RETURNING id
                """, Long.class, billingPlanId, Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0)));
        Long subscriptionContractId = jdbcTemplate.queryForObject("""
                INSERT INTO subscription_contract (
                    user_id, billing_plan_id, billing_plan_version_id, provider,
                    status, current_period_start, current_period_end, cancel_at_period_end
                ) VALUES (?, ?, ?, 'ALIPAY', 'ACTIVE', ?, ?, FALSE)
                RETURNING id
                """, Long.class, userId, billingPlanId, billingPlanVersionId,
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 5, 1, 0, 0)));
        Long billingEventId = jdbcTemplate.queryForObject("""
                INSERT INTO billing_event (
                    user_id, event_type, source_type, source_id, idempotency_key,
                    delta_amount_micros, bucket_code, occurred_at
                ) VALUES (?, 'SUBSCRIPTION_QUOTA_GRANTED', 'TEST', ?, ?, ?, 'KB_QUERY_CREDITS', ?)
                RETURNING id
                """, Long.class, userId, "grant-" + UUID.randomUUID(), "grant-key-" + UUID.randomUUID(), 300_000L,
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0)));
        jdbcTemplate.update("""
                INSERT INTO subscription_quota_grant (
                    subscription_contract_id, source_billing_event_id, period_start, period_end,
                    bucket_code, granted_amount_micros, used_amount_micros, expired_amount_micros
                ) VALUES (?, ?, ?, ?, 'KB_QUERY_CREDITS', ?, 50_000, 0)
                """,
                subscriptionContractId,
                billingEventId,
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 5, 1, 0, 0)),
                300_000L
        );
        String username = findUsername(userId);

        mockMvc.perform(get("/api/v1/users/me/usage-overview")
                        .principal(userPrincipal(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscription.active").value(true))
                .andExpect(jsonPath("$.data.subscription.bucketCount").value(1))
                .andExpect(jsonPath("$.data.subscription.buckets[0].bucketCode").value("KB_QUERY_CREDITS"))
                .andExpect(jsonPath("$.data.balance.subscriptionAvailableCreditsMicros").value(250000))
                .andExpect(jsonPath("$.data.defaultWindow.windowType").value("CURRENT_SUBSCRIPTION_PERIOD"));
    }

    @Test
    void experience_UsageHistory_UsesSubscriptionGrantAmountFromQuotaGrant() throws Exception {
        Long userId = createUserId("usage-grant-history");
        Long billingPlanId = jdbcTemplate.queryForObject("""
                INSERT INTO billing_plan (plan_code, tier_code, display_name, active)
                VALUES (?, 'PRO', 'Pro Plan', TRUE)
                RETURNING id
                """, Long.class, "pro-monthly-" + UUID.randomUUID());
        Long billingPlanVersionId = jdbcTemplate.queryForObject("""
                INSERT INTO billing_plan_version (
                    billing_plan_id, version_no, billing_cycle, amount, currency,
                    sale_enabled, renewal_enabled, effective_from, effective_to
                ) VALUES (?, 1, 'MONTHLY', 99.000000, 'CNY', TRUE, TRUE, ?, NULL)
                RETURNING id
                """, Long.class, billingPlanId, Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0)));
        Long subscriptionContractId = jdbcTemplate.queryForObject("""
                INSERT INTO subscription_contract (
                    user_id, billing_plan_id, billing_plan_version_id, provider,
                    status, current_period_start, current_period_end, cancel_at_period_end
                ) VALUES (?, ?, ?, 'ALIPAY', 'ACTIVE', ?, ?, FALSE)
                RETURNING id
                """, Long.class, userId, billingPlanId, billingPlanVersionId,
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 5, 1, 0, 0)));
        Long billingEventId = jdbcTemplate.queryForObject("""
                INSERT INTO billing_event (
                    user_id, event_type, source_type, source_id, idempotency_key,
                    delta_amount_micros, bucket_code, occurred_at
                ) VALUES (?, 'SUBSCRIPTION_QUOTA_GRANTED', 'TEST', ?, ?, 0, 'KB_QUERY_CREDITS', ?)
                RETURNING id
                """, Long.class, userId, "grant-" + UUID.randomUUID(), "grant-key-" + UUID.randomUUID(),
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0)));
        jdbcTemplate.update("""
                INSERT INTO subscription_quota_grant (
                    subscription_contract_id, source_billing_event_id, period_start, period_end,
                    bucket_code, granted_amount_micros, used_amount_micros, expired_amount_micros
                ) VALUES (?, ?, ?, ?, 'KB_QUERY_CREDITS', ?, 0, 0)
                """,
                subscriptionContractId,
                billingEventId,
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 5, 1, 0, 0)),
                300_000L
        );
        String username = findUsername(userId);

        mockMvc.perform(get("/api/v1/users/me/usage-history")
                        .principal(userPrincipal(username))
                        .param("category", "GRANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].entryType").value("GRANT"))
                .andExpect(jsonPath("$.data.content[0].sourceType").value("SUBSCRIPTION"))
                .andExpect(jsonPath("$.data.content[0].creditsDeltaMicros").value(300000))
                .andExpect(jsonPath("$.data.content[0].creditsDelta").value("300.000"))
                .andExpect(jsonPath("$.data.content[0].grant.eventType").value("SUBSCRIPTION_QUOTA_GRANTED"));
    }

    @Test
    void experience_NegativePurchasedBalance_ShowsNegativeTotalAndZeroSpendable() throws Exception {
        Long userId = createUserId("usage-negative");
        jdbcTemplate.update("""
                INSERT INTO billing_event (
                    external_id, user_id, event_type, source_type, source_id, idempotency_key,
                    delta_amount_micros, bucket_code, occurred_at
                ) VALUES (?, ?, 'CREDIT_PURCHASE_GRANTED', 'TEST', ?, ?, ?, NULL, ?)
                """,
                UUID.randomUUID(),
                userId,
                "purchase-" + UUID.randomUUID(),
                "purchase-key-" + UUID.randomUUID(),
                100_000L,
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0))
        );
        Long usageEventId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_usage_event (
                    external_id, user_id, purpose, usage_family, provider, model_code,
                    resource_type, resource_external_id, operation_id, request_count,
                    total_tokens, charge_bucket, business_outcome, dedupe_key, occurred_at, created_at
                ) VALUES (?, ?, 'kb_query', 'CHAT', 'TEST', 'test-model',
                    'KNOWLEDGE_BASE_QUERY', ?, ?, 1, 1, 'KB_QUERY_CREDITS', 'SUCCESS', ?, ?, ?)
                RETURNING id
                """, Long.class,
                UUID.randomUUID(),
                userId,
                "kb-" + UUID.randomUUID(),
                "op-" + UUID.randomUUID(),
                "dedupe-" + UUID.randomUUID(),
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 2, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 2, 0, 0))
        );
        jdbcTemplate.update("""
                INSERT INTO llm_usage_credit_ledger (
                    usage_event_id, charge_bucket, charged_credits_micros, charge_status, metadata
                ) VALUES (?, 'KB_QUERY_CREDITS', ?, 'CHARGEABLE', CAST(? AS jsonb))
                """, usageEventId, 120_000L, "{\"subscriptionAllocatedMicros\":0,\"purchasedAllocatedMicros\":120000}");
        jdbcTemplate.update("""
                INSERT INTO credit_wallet (user_id, purchased_balance_micros)
                VALUES (?, ?)
                """, userId, -20_000L);
        String username = findUsername(userId);

                mockMvc.perform(get("/api/v1/users/me/usage-overview")
                        .principal(userPrincipal(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance.totalCreditsMicros").value(-20000))
                .andExpect(jsonPath("$.data.balance.spendableCreditsMicros").value(0))
                .andExpect(jsonPath("$.data.balance.purchasedAvailableCreditsMicros").value(0))
                .andExpect(jsonPath("$.data.balance.purchasedTotalCreditsMicros").value(100000))
                .andExpect(jsonPath("$.data.balance.purchasedUsedCreditsMicros").value(120000))
                .andExpect(jsonPath("$.data.balance.rawPurchasedBalanceMicros").value(-20000))
                .andExpect(jsonPath("$.data.balance.negative").value(true));
    }

    @Test
    void experience_UsageHistory_OmitsZeroCreditUsageEntries() throws Exception {
        Long userId = createUserId("usage-zero-charge");
        LocalDateTime occurredAt = LocalDateTime.of(2026, 4, 9, 8, 0);
        Long usageEventId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_usage_event (
                    external_id, user_id, purpose, usage_family, provider, model_code,
                    resource_type, resource_external_id, operation_id, request_count,
                    total_tokens, charge_bucket, business_outcome, dedupe_key, occurred_at, created_at
                ) VALUES (?, ?, 'kb_query', 'EMBEDDING', 'TEST', 'test-model',
                    'KNOWLEDGE_BASE', ?, ?, 1, 1, 'KB_QUERY_CREDITS', 'SUCCESS', ?, ?, ?)
                RETURNING id
                """, Long.class,
                UUID.randomUUID(),
                userId,
                "kb-" + UUID.randomUUID(),
                "op-" + UUID.randomUUID(),
                "dedupe-" + UUID.randomUUID(),
                Timestamp.valueOf(occurredAt),
                Timestamp.valueOf(occurredAt));
        jdbcTemplate.update("""
                INSERT INTO llm_usage_credit_ledger (
                    usage_event_id, charge_bucket, charged_credits_micros, charge_status
                ) VALUES (?, 'KB_QUERY_CREDITS', 0, 'CHARGEABLE')
                """, usageEventId);
        String username = findUsername(userId);

        mockMvc.perform(get("/api/v1/users/me/usage-history")
                        .principal(userPrincipal(username))
                        .param("windowType", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    /**
     * Regression test for usage-history time consistency bug.
     * <p>
     * Scenario: A usage event with business time (occurred_at) in the DEFAULT window,
     * but created_at delayed due to async processing (set to 8 hours after request time).
     * <p>
     * Current behavior (BUG): SQL uses created_at for time filtering, so this usage is missed.
     * Expected behavior: SQL should use occurred_at (business time), so this usage appears.
     * <p>
     * Note: This test will FAIL until SQL is updated to use occurred_at (Task 3).
     */
    @Test
    void experience_UsageHistory_DefaultWindow_ShowsUsageByBusinessTimeNotCreatedTime() throws Exception {
        Long userId = createUserId("usage-time-regression");
        // Business time is within DEFAULT window (request time is 2026-04-09T09:00:00Z)
        // DEFAULT window for non-subscription user is last 30 days: 2026-03-10T09:00 to 2026-04-09T09:00
        LocalDateTime businessOccurredAt = LocalDateTime.of(2026, 4, 9, 8, 30); // 30 min before request, in window

        // Insert usage event with:
        // - occurred_at = business time (in window)
        // - created_at = delayed write time (outside window, simulating async delay)
        // This exposes the bug: if SQL filters by created_at, the usage will be missed
        String resourceExternalId = "kb-" + UUID.randomUUID();
        Long usageEventId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_usage_event (
                    external_id, user_id, purpose, usage_family, provider, model_code,
                    resource_type, resource_external_id, operation_id, request_count,
                    total_tokens, charge_bucket, business_outcome, dedupe_key,
                    occurred_at, created_at
                ) VALUES (?, ?, 'kb_query', 'EMBEDDING', 'TEST', 'test-model',
                    'KNOWLEDGE_BASE_QUERY', ?, ?, 1, 100, 'KB_QUERY_CREDITS', 'SUCCESS', ?,
                    ?, ?)
                RETURNING id
                """, Long.class,
                UUID.randomUUID(),
                userId,
                resourceExternalId,
                "query-" + UUID.randomUUID(),
                "dedupe-" + UUID.randomUUID(),
                // occurred_at = business time (in window)
                Timestamp.valueOf(businessOccurredAt),
                // created_at = delayed write time (outside window)
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 9, 17, 0))
        );

        // Insert chargeable ledger entry with 1.248 credits
        jdbcTemplate.update("""
                INSERT INTO llm_usage_credit_ledger (
                    usage_event_id, charge_bucket, charged_credits_micros, charge_status, metadata
                ) VALUES (?, 'KB_QUERY_CREDITS', ?, 'CHARGEABLE', CAST(? AS jsonb))
                """,
                usageEventId,
                1248L, // 1.248 credits = 1248 micros
                "{\"subscriptionAllocatedMicros\":0,\"purchasedAllocatedMicros\":1248}"
        );

        // Grant some purchased credits so the user has balance
        jdbcTemplate.update("""
                INSERT INTO billing_event (
                    external_id, user_id, event_type, source_type, source_id, idempotency_key,
                    delta_amount_micros, bucket_code, occurred_at
                ) VALUES (?, ?, 'CREDIT_PURCHASE_GRANTED', 'TEST', ?, ?, ?, NULL, ?)
                """,
                UUID.randomUUID(),
                userId,
                "purchase-" + UUID.randomUUID(),
                "purchase-key-" + UUID.randomUUID(),
                10_000L,
                Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0))
        );

        String username = findUsername(userId);

        // Expected after fix: usage should appear because its business time is in window
        // Current behavior (bug): usage is missed because SQL filters by created_at
        mockMvc.perform(get("/api/v1/users/me/usage-history")
                        .principal(userPrincipal(username))
                        .param("windowType", "DEFAULT")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                // Should return 2 entries: 1 USAGE (by business time) + 1 GRANT (billing event)
                // After fix: USAGE appears because occurred_at is in window
                .andExpect(jsonPath("$.data.content.length()").value(2))
                // First entry should be USAGE (sorted by occurred_at DESC)
                .andExpect(jsonPath("$.data.content[0].entryType").value("USAGE"))
                .andExpect(jsonPath("$.data.content[0].usage.resourceExternalId").value(resourceExternalId))
                .andExpect(jsonPath("$.data.content[0].creditsDeltaMicros").value(-1248))
                // Second entry should be GRANT
                .andExpect(jsonPath("$.data.content[1].entryType").value("GRANT"));
    }

    private String createUser(String prefix) {
        return findUsername(createUserId(prefix));
    }

    private Long createUserId(String prefix) {
        String username = prefix + "-" + UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, username, username + "@example.com", "hashed", "zh-CN", "Asia/Shanghai");
    }

    private String findUsername(Long userId) {
        return jdbcTemplate.queryForObject("SELECT username FROM users WHERE id = ?", String.class, userId);
    }

    private UsernamePasswordAuthenticationToken userPrincipal(String username) {
        return new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                AuthorityUtils.createAuthorityList("ROLE_USER")
        );
    }
}
