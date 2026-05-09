package com.josh.interviewj.usage.repository;

import com.josh.interviewj.IntelligentInterviewJApplication;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.usage.model.BillingUnit;
import com.josh.interviewj.usage.model.ChargeBucket;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.model.UserCreditPeriod;
import com.josh.interviewj.usage.model.UserCreditPolicy;
import com.josh.interviewj.usage.service.UsageBusinessOutcome;
import com.josh.interviewj.usage.service.UsageOperationContext;
import com.josh.interviewj.usage.service.UsageSettlementService;
import com.josh.interviewj.usage.support.UsageIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = IntelligentInterviewJApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class UserCreditsBillingIntegrationTest extends UsageIntegrationTestBase {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRESQL = POSTGRES;

    @Container
    static final com.redis.testcontainers.RedisContainer REDIS_CONTAINER = REDIS;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private UsageSettlementService usageSettlementService;

    @Autowired
    private UserCreditPeriodRepository userCreditPeriodRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        LocalDateTime periodStart = LocalDateTime.of(2026, 4, 1, 0, 0);
        LocalDateTime periodEnd = LocalDateTime.of(2026, 5, 1, 0, 0);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, "settle-" + UUID.randomUUID(), "settle@example.com", "hashed", "zh-CN", "Asia/Shanghai");
        jdbcTemplate.update("""
                INSERT INTO llm_model_pricing_version (
                    provider, model_code, usage_family, effective_from, effective_to,
                    billing_unit, prompt_token_price, completion_token_price, cached_token_price, request_price, currency
                ) VALUES ('dispatcher_rc', 'gpt-5.4', 'CHAT', ?, NULL, 'TOKEN_AND_REQUEST', 0.001000, 0.002000, 0, 0.100000, 'USD')
                """, java.sql.Timestamp.valueOf(periodStart));
        jdbcTemplate.update("""
                INSERT INTO usage_credit_policy_version (
                    purpose, charge_bucket, usage_family, effective_from, effective_to,
                    billing_unit, prompt_token_ratio, completion_token_ratio, request_ratio
                ) VALUES ('analysis', 'RESUME_CREDITS', 'CHAT', ?, NULL, 'TOKEN_AND_REQUEST', 1.000000, 1.000000, 500.000000)
                """, java.sql.Timestamp.valueOf(periodStart));
        jdbcTemplate.update("""
                INSERT INTO user_credit_policy (
                    user_id, effective_from, effective_to,
                    resume_credits_limit_micros, kb_query_credits_limit_micros,
                    kb_ingestion_credits_limit_micros, interview_credits_limit_micros
                ) VALUES (?, ?, NULL, 500000, 300000, 1000000, 400000)
                """, userId, java.sql.Timestamp.valueOf(periodStart));

        Long billingPlanId = jdbcTemplate.queryForObject("""
                INSERT INTO billing_plan (plan_code, tier_code, display_name, active)
                VALUES (?, 'PRO', 'Pro Plan', TRUE)
                RETURNING id
                """, Long.class, "plan-" + UUID.randomUUID());
        Long billingPlanVersionId = jdbcTemplate.queryForObject("""
                INSERT INTO billing_plan_version (
                    billing_plan_id, version_no, billing_cycle, amount, currency,
                    sale_enabled, renewal_enabled, effective_from, effective_to
                ) VALUES (?, 1, 'MONTHLY', 99.000000, 'CNY', TRUE, TRUE, ?, NULL)
                RETURNING id
                """, Long.class, billingPlanId, java.sql.Timestamp.valueOf(periodStart));
        Long subscriptionContractId = jdbcTemplate.queryForObject("""
                INSERT INTO subscription_contract (
                    user_id, billing_plan_id, billing_plan_version_id,
                    provider, status, current_period_start, current_period_end, cancel_at_period_end
                ) VALUES (?, ?, ?, 'ALIPAY', 'ACTIVE', ?, ?, FALSE)
                RETURNING id
                """, Long.class, userId, billingPlanId, billingPlanVersionId,
                java.sql.Timestamp.valueOf(periodStart), java.sql.Timestamp.valueOf(periodEnd));
        Long grantBillingEventId = jdbcTemplate.queryForObject("""
                INSERT INTO billing_event (
                    user_id, event_type, source_type, source_id, idempotency_key,
                    delta_amount_micros, bucket_code, occurred_at
                ) VALUES (?, 'SUBSCRIPTION_QUOTA_GRANTED', 'TEST', ?, ?, ?, 'RESUME_CREDITS', ?)
                RETURNING id
                """, Long.class, userId,
                "quota-grant-" + UUID.randomUUID(),
                "quota-grant-key-" + UUID.randomUUID(),
                5_000L,
                java.sql.Timestamp.valueOf(periodStart));
        jdbcTemplate.update("""
                INSERT INTO subscription_quota_grant (
                    subscription_contract_id, source_billing_event_id, period_start, period_end,
                    bucket_code, granted_amount_micros, used_amount_micros, expired_amount_micros
                ) VALUES (?, ?, ?, ?, 'RESUME_CREDITS', ?, 0, 0)
                """,
                subscriptionContractId,
                grantBillingEventId,
                java.sql.Timestamp.valueOf(periodStart),
                java.sql.Timestamp.valueOf(periodEnd),
                5_000L
        );
    }

    @Test
    void settleSuccess_CreatesUsageAndPeriodIncrement() {
        usageSettlementService.settle(new UsageOperationContext(
                "analysis",
                "dispatcher_rc",
                "gpt-5.4",
                new ProviderUsage(UsageFamily.CHAT, 1L, 100L, 20L, 120L, 0L),
                "RESUME_ANALYSIS_REPORT",
                UUID.randomUUID().toString(),
                "op-1",
                userId,
                UsageBusinessOutcome.SUCCESS,
                null
        ));

        UserCreditPeriod period = userCreditPeriodRepository.findAll().getFirst();
        assertThat(period.getResumeCreditsUsedMicros()).isPositive();
    }
}
