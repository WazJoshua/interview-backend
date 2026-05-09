package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.model.SubscriptionQuotaGrant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = SubscriptionQuotaGrantRepositoryTest.TestBillingJpaApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class SubscriptionQuotaGrantRepositoryTest extends BillingRepositoryIntegrationTestBase {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRESQL = POSTGRES;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BillingPlanRepository billingPlanRepository;

    @Autowired
    private BillingPlanVersionRepository billingPlanVersionRepository;

    @Autowired
    private SubscriptionContractRepository subscriptionContractRepository;

    @Autowired
    private BillingEventRepository billingEventRepository;

    @Autowired
    private SubscriptionQuotaGrantRepository subscriptionQuotaGrantRepository;

    private Long userId;
    private BillingPlan billingPlan;
    private BillingPlanVersion billingPlanVersion;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, "billing-user-" + UUID.randomUUID(), "billing@example.com", "hashed", "zh-CN", "Asia/Shanghai");

        billingPlan = billingPlanRepository.save(BillingPlan.builder()
                .externalId(UUID.randomUUID())
                .planCode("plus-monthly-" + UUID.randomUUID())
                .tierCode("plus")
                .displayName("Plus")
                .active(true)
                .metadata("{}")
                .build());
        billingPlanVersion = billingPlanVersionRepository.save(BillingPlanVersion.builder()
                .externalId(UUID.randomUUID())
                .billingPlanId(billingPlan.getId())
                .versionNo(1)
                .billingCycle("MONTHLY")
                .amount(new BigDecimal("29.900000"))
                .currency("USD")
                .saleEnabled(true)
                .renewalEnabled(true)
                .effectiveFrom(LocalDateTime.of(2026, 4, 1, 0, 0))
                .metadata("{}")
                .build());
    }

    @Test
    void save_WhenSameContractPeriodAndBucketTwice_RejectsDuplicateGrant() {
        SubscriptionContract contract = subscriptionContractRepository.save(openContract("ref-1"));
        BillingEvent firstEvent = billingEventRepository.save(billingEvent("grant-1"));
        BillingEvent secondEvent = billingEventRepository.save(billingEvent("grant-2"));

        subscriptionQuotaGrantRepository.saveAndFlush(grant(contract.getId(), firstEvent.getId()));

        assertThatThrownBy(() -> subscriptionQuotaGrantRepository.saveAndFlush(grant(contract.getId(), secondEvent.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_WhenSecondOpenContractInserted_RejectsOpenContractUniqueness() {
        subscriptionContractRepository.saveAndFlush(openContract("ref-2"));

        assertThatThrownBy(() -> subscriptionContractRepository.saveAndFlush(openContract("ref-3")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private SubscriptionContract openContract(String providerSubscriptionRef) {
        return SubscriptionContract.builder()
                .externalId(UUID.randomUUID())
                .userId(userId)
                .billingPlanId(billingPlan.getId())
                .billingPlanVersionId(billingPlanVersion.getId())
                .provider("mockpay")
                .providerSubscriptionRef(providerSubscriptionRef)
                .status(SubscriptionContractStatus.ACTIVE)
                .currentPeriodStart(LocalDateTime.of(2026, 4, 1, 0, 0))
                .currentPeriodEnd(LocalDateTime.of(2026, 5, 1, 0, 0))
                .cancelAtPeriodEnd(false)
                .metadata("{}")
                .build();
    }

    private BillingEvent billingEvent(String sourceId) {
        return BillingEvent.builder()
                .externalId(UUID.randomUUID())
                .userId(userId)
                .eventType(BillingEventType.SUBSCRIPTION_QUOTA_GRANTED)
                .sourceType("PAYMENT_EVENT")
                .sourceId(sourceId)
                .idempotencyKey("billing-event-" + sourceId)
                .deltaAmountMicros(500_000L)
                .bucketCode("RESUME_CREDITS")
                .occurredAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .metadata("{\"reason\":\"grant\"}")
                .build();
    }

    private SubscriptionQuotaGrant grant(Long contractId, Long billingEventId) {
        return SubscriptionQuotaGrant.builder()
                .externalId(UUID.randomUUID())
                .subscriptionContractId(contractId)
                .sourceBillingEventId(billingEventId)
                .periodStart(LocalDateTime.of(2026, 4, 1, 0, 0))
                .periodEnd(LocalDateTime.of(2026, 5, 1, 0, 0))
                .bucketCode("RESUME_CREDITS")
                .grantedAmountMicros(500_000L)
                .usedAmountMicros(0L)
                .expiredAmountMicros(0L)
                .metadata("{}")
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {
            BillingPlan.class,
            BillingPlanVersion.class,
            SubscriptionContract.class,
            BillingEvent.class,
            SubscriptionQuotaGrant.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            BillingPlanRepository.class,
            BillingPlanVersionRepository.class,
            SubscriptionContractRepository.class,
            BillingEventRepository.class,
            SubscriptionQuotaGrantRepository.class
    })
    static class TestBillingJpaApplication {
    }
}
