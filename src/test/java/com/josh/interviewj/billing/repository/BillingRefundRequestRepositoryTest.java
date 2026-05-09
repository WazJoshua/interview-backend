package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.BillingRefundRequest;
import com.josh.interviewj.billing.model.BillingRefundStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = BillingRefundRequestRepositoryTest.TestBillingRefundJpaApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class BillingRefundRequestRepositoryTest extends BillingRepositoryIntegrationTestBase {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRESQL = POSTGRES;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BillingRefundRequestRepository billingRefundRequestRepository;

    private Long userId;
    private Long paymentOrderId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, "refund-user-" + UUID.randomUUID(), "refund-user@example.com", "hashed", "zh-CN", "Asia/Shanghai");
        paymentOrderId = jdbcTemplate.queryForObject("""
                INSERT INTO payment_order (
                    external_id, order_no, user_id, order_type, biz_ref_type, biz_ref_id,
                    provider, amount, currency, status, idempotency_key, provider_order_ref,
                    pricing_snapshot, entitlement_snapshot, payable_activated_at, expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                RETURNING id
                """, Long.class,
                UUID.randomUUID(),
                "po_refund_" + UUID.randomUUID().toString().replace("-", ""),
                userId,
                "CREDIT_PURCHASE",
                "CREDIT_PURCHASE_SKU",
                "credits-basic",
                "alipay",
                new BigDecimal("9.900000"),
                "USD",
                "SUCCEEDED",
                "idem-refund",
                "provider-refund",
                "{\"snapshotType\":\"PURCHASE\"}",
                "[]",
                LocalDateTime.of(2026, 4, 1, 0, 0),
                LocalDateTime.of(2026, 4, 1, 0, 30)
        );
    }

    @Test
    void save_WhenOpenRefundRequestAlreadyExists_RejectsSecondOpenRequest() {
        billingRefundRequestRepository.saveAndFlush(refundRequest("refund-1"));

        assertThatThrownBy(() -> billingRefundRequestRepository.saveAndFlush(refundRequest("refund-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private BillingRefundRequest refundRequest(String reason) {
        return BillingRefundRequest.builder()
                .externalId(UUID.randomUUID())
                .paymentOrderId(paymentOrderId)
                .userId(userId)
                .requestedAmount(new BigDecimal("9.900000"))
                .currency("USD")
                .reason(reason)
                .status(BillingRefundStatus.PENDING_REVIEW)
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BillingRefundRequest.class)
    @EnableJpaRepositories(basePackageClasses = BillingRefundRequestRepository.class)
    static class TestBillingRefundJpaApplication {
    }
}
