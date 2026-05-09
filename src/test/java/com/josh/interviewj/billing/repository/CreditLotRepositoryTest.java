package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.CreditLot;
import com.josh.interviewj.billing.model.CreditLotStatus;
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

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = CreditLotRepositoryTest.TestBillingJpaApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class CreditLotRepositoryTest extends BillingRepositoryIntegrationTestBase {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRESQL = POSTGRES;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BillingEventRepository billingEventRepository;

    @Autowired
    private CreditLotRepository creditLotRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, "credit-lot-user-" + UUID.randomUUID(), "credit-lot@example.com", "hashed", "zh-CN", "Asia/Shanghai");
    }

    @Test
    void save_WhenSourceBillingEventReused_RejectsDuplicateLot() {
        BillingEvent billingEvent = billingEventRepository.saveAndFlush(billingEvent("purchase-1"));
        creditLotRepository.saveAndFlush(lot(billingEvent.getId(), 800_000L));

        assertThatThrownBy(() -> creditLotRepository.saveAndFlush(lot(billingEvent.getId(), 400_000L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findConsumableLotsForUpdate_ReturnsCreatedOrderOnly() {
        BillingEvent earlierEvent = billingEventRepository.saveAndFlush(billingEvent("purchase-early"));
        BillingEvent laterEvent = billingEventRepository.saveAndFlush(billingEvent("purchase-late"));

        creditLotRepository.saveAndFlush(lot(earlierEvent.getId(), 500_000L));
        creditLotRepository.saveAndFlush(lot(laterEvent.getId(), 600_000L));

        assertThat(creditLotRepository.findConsumableLotsForUpdate(
                userId,
                CreditLotStatus.ACTIVE,
                LocalDateTime.of(2026, 4, 2, 0, 0)
        ))
                .extracting(CreditLot::getSourceBillingEventId)
                .containsExactly(earlierEvent.getId(), laterEvent.getId());
    }

    private BillingEvent billingEvent(String sourceId) {
        return BillingEvent.builder()
                .externalId(UUID.randomUUID())
                .userId(userId)
                .eventType(BillingEventType.CREDIT_PURCHASE_GRANTED)
                .sourceType("PAYMENT_EVENT")
                .sourceId(sourceId)
                .idempotencyKey("credit-lot-event-" + sourceId)
                .deltaAmountMicros(800_000L)
                .occurredAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .metadata("{}")
                .build();
    }

    private CreditLot lot(Long sourceBillingEventId, long amountMicros) {
        return CreditLot.builder()
                .externalId(UUID.randomUUID())
                .userId(userId)
                .sourceBillingEventId(sourceBillingEventId)
                .originalAmountMicros(amountMicros)
                .remainingAmountMicros(amountMicros)
                .status(CreditLotStatus.ACTIVE)
                .metadata("{}")
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {
            BillingEvent.class,
            CreditLot.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            BillingEventRepository.class,
            CreditLotRepository.class
    })
    static class TestBillingJpaApplication {
    }
}
