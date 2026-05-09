package com.josh.interviewj.usage.repository;

import com.josh.interviewj.IntelligentInterviewJApplication;
import com.josh.interviewj.usage.dto.request.UpdateUserCreditPolicyRequest;
import com.josh.interviewj.usage.dto.request.AdminUsageEventsQuery;
import com.josh.interviewj.usage.dto.response.AdminCreditLedgerResponse;
import com.josh.interviewj.usage.dto.response.AdminUserCreditPolicyResponse;
import com.josh.interviewj.usage.service.AdminCreditsBillingService;
import com.josh.interviewj.usage.support.UsageIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = IntelligentInterviewJApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class AdminCreditsBillingIntegrationTest extends UsageIntegrationTestBase {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRESQL = POSTGRES;

    @Container
    static final com.redis.testcontainers.RedisContainer REDIS_CONTAINER = REDIS;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminCreditsBillingService adminCreditsBillingService;

    private Long adminUserId;
    private UUID userExternalId;

    @BeforeEach
    void setUp() {
        adminUserId = jdbcTemplate.queryForObject("""
                INSERT INTO users (external_id, username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, UUID.randomUUID(), "admin-" + UUID.randomUUID(), "admin-" + UUID.randomUUID() + "@example.com", "hashed", "zh-CN", "Asia/Shanghai");
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", adminUserId, "ADMIN");
        userExternalId = jdbcTemplate.queryForObject("""
                INSERT INTO users (external_id, username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING external_id
                """, UUID.class, UUID.randomUUID(), "policy-" + UUID.randomUUID(), "policy-" + UUID.randomUUID() + "@example.com", "hashed", "zh-CN", "Asia/Shanghai");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE external_id = ?", Long.class, userExternalId);
        jdbcTemplate.update("""
                INSERT INTO user_credit_policy (
                    user_id, effective_from, effective_to,
                    resume_credits_limit_micros, kb_query_credits_limit_micros,
                    kb_ingestion_credits_limit_micros, interview_credits_limit_micros
                ) VALUES (?, ?, NULL, 200000, 300000, 1000000, 200000)
                """, userId, java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 0, 0)));
    }

    @Test
    void updateUserCreditPolicy_ReturnsPendingPolicy() {
        UpdateUserCreditPolicyRequest request = new UpdateUserCreditPolicyRequest();
        request.setEffectiveFrom(OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        request.setResumeCreditsLimitMicros(240000L);
        request.setKbQueryCreditsLimitMicros(320000L);
        request.setKbIngestionCreditsLimitMicros(1000000L);
        request.setInterviewCreditsLimitMicros(240000L);

        AdminUserCreditPolicyResponse response = adminCreditsBillingService.updateUserCreditPolicy(adminUserId, userExternalId, request);

        assertThat(response.getPendingPolicy()).isNotNull();
        assertThat(response.getPendingPolicy().getResumeCreditsLimitMicros()).isEqualTo(240000L);
    }

    @Test
    void getCreditLedger_WhenOptionalFiltersMissing_ReturnsRows() {
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE external_id = ?", Long.class, userExternalId);
        LocalDateTime occurredAt = LocalDateTime.of(2026, 4, 1, 0, 0);
        Long usageEventId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_usage_event (
                    user_id, usage_family, purpose, provider, model_code,
                    resource_type, resource_external_id, operation_id,
                    request_count, prompt_tokens, completion_tokens, total_tokens,
                    charge_bucket, business_outcome, dedupe_key, occurred_at, created_at
                ) VALUES (?, 'CHAT', 'analysis', 'dispatcher_rc', 'gpt-5.4',
                          'RESUME_ANALYSIS_REPORT', ?, ?, 1, 100, 20, 120,
                          'RESUME_CREDITS', 'SUCCESS', ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId,
                UUID.randomUUID().toString(),
                "op-" + UUID.randomUUID(),
                "dedupe-" + UUID.randomUUID(),
                java.sql.Timestamp.valueOf(occurredAt),
                java.sql.Timestamp.valueOf(occurredAt)
        );
        jdbcTemplate.update("""
                INSERT INTO llm_usage_credit_ledger (
                    usage_event_id, charge_bucket, charged_credits_micros, charge_status
                ) VALUES (?, 'RESUME_CREDITS', 1500, 'CHARGEABLE')
                """, usageEventId);

        AdminUsageEventsQuery query = new AdminUsageEventsQuery();
        query.setFrom(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        query.setTo(OffsetDateTime.of(2026, 4, 2, 0, 0, 0, 0, ZoneOffset.UTC));

        Page<AdminCreditLedgerResponse> result = adminCreditsBillingService.getCreditLedger(query);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getPurpose()).isEqualTo("analysis");
        assertThat(result.getContent().getFirst().getChargeBucket()).isEqualTo("RESUME_CREDITS");
    }

    @Test
    void getCreditLedger_WhenOccurredAtInWindowButCreatedAtDelayed_ReturnsUsage() {
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE external_id = ?", Long.class, userExternalId);
        // occurred_at 在窗口内，但 created_at 晚于窗口结束时间（模拟异步落库延迟）
        // 使用不同的时间窗口（2026-04-05），避免与其他测试数据重叠
        LocalDateTime occurredAt = LocalDateTime.of(2026, 4, 5, 12, 0);
        LocalDateTime createdAtDelayed = LocalDateTime.of(2026, 4, 7, 8, 0); // 延后到窗口外
        Long usageEventId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_usage_event (
                    user_id, usage_family, purpose, provider, model_code,
                    resource_type, resource_external_id, operation_id,
                    request_count, prompt_tokens, completion_tokens, total_tokens,
                    charge_bucket, business_outcome, dedupe_key, occurred_at, created_at
                ) VALUES (?, 'CHAT', 'kb_query', 'dispatcher_rc', 'gpt-5.4',
                          'KB_QUERY_SESSION', ?, ?, 1, 200, 50, 250,
                          'KB_QUERY_CREDITS', 'SUCCESS', ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId,
                UUID.randomUUID().toString(),
                "op-" + UUID.randomUUID(),
                "dedupe-" + UUID.randomUUID(),
                java.sql.Timestamp.valueOf(occurredAt),
                java.sql.Timestamp.valueOf(createdAtDelayed)
        );
        jdbcTemplate.update("""
                INSERT INTO llm_usage_credit_ledger (
                    usage_event_id, charge_bucket, charged_credits_micros, charge_status
                ) VALUES (?, 'KB_QUERY_CREDITS', 2500, 'CHARGEABLE')
                """, usageEventId);

        // 查询窗口只覆盖 occurred_at 所在的时间（不覆盖其他测试的数据）
        AdminUsageEventsQuery query = new AdminUsageEventsQuery();
        query.setFrom(OffsetDateTime.of(2026, 4, 5, 0, 0, 0, 0, ZoneOffset.UTC));
        query.setTo(OffsetDateTime.of(2026, 4, 6, 0, 0, 0, 0, ZoneOffset.UTC));

        Page<AdminCreditLedgerResponse> result = adminCreditsBillingService.getCreditLedger(query);

        // 如果查询口径正确（使用 occurred_at），应该返回这条记录
        // 如果错误地使用 created_at，则会漏掉（因为 created_at 在窗口外）
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getPurpose()).isEqualTo("kb_query");
        assertThat(result.getContent().getFirst().getChargeBucket()).isEqualTo("KB_QUERY_CREDITS");
        assertThat(result.getContent().getFirst().getChargedCreditsMicros()).isEqualTo(2500L);
    }
}
