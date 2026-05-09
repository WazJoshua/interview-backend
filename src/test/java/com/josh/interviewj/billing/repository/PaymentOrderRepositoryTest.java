package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PaymentOrderRepositoryTest.TestBillingJpaApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class PaymentOrderRepositoryTest extends BillingRepositoryIntegrationTestBase {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRESQL = POSTGRES;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    private Long firstUserId;
    private Long secondUserId;

    @BeforeEach
    void setUp() {
        firstUserId = insertUser("payment-order-user-a-" + UUID.randomUUID(), "payment-order-a@example.com");
        secondUserId = insertUser("payment-order-user-b-" + UUID.randomUUID(), "payment-order-b@example.com");
    }

    @Test
    void save_WhenDifferentUsersReuseSameIdempotencyKey_AllowsBothOrders() {
        paymentOrderRepository.saveAndFlush(paymentOrder(firstUserId, "po-first", "shared-idem"));
        paymentOrderRepository.saveAndFlush(paymentOrder(secondUserId, "po-second", "shared-idem"));

        assertThat(paymentOrderRepository.findByUserIdAndIdempotencyKey(firstUserId, "shared-idem"))
                .map(PaymentOrder::getOrderNo)
                .contains("po-first");
        assertThat(paymentOrderRepository.findByUserIdAndIdempotencyKey(secondUserId, "shared-idem"))
                .map(PaymentOrder::getOrderNo)
                .contains("po-second");
    }

    @Test
    void findActivePayableOrdersByUserId_WhenSingleActivatedOrderExists_ReturnsIt() {
        insertPaymentOrder(firstUserId, "po-new", PaymentOrderType.CREDIT_PURCHASE, PaymentOrderStatus.PENDING_PROVIDER,
                LocalDateTime.of(2026, 4, 1, 10, 0), LocalDateTime.of(2026, 4, 1, 10, 0),
                LocalDateTime.of(2026, 4, 1, 11, 0));
        insertPaymentOrder(firstUserId, "po-inactive", PaymentOrderType.CREDIT_PURCHASE, PaymentOrderStatus.EXPIRED,
                null, LocalDateTime.of(2026, 4, 1, 9, 0),
                LocalDateTime.of(2026, 4, 1, 10, 0));

        assertThat(paymentOrderRepository.findActivePayableOrdersByUserId(firstUserId))
                .extracting(PaymentOrder::getOrderNo)
                .containsExactly("po-new");
    }

    @Test
    void findActivePayableOrdersByUserId_WhenRenewalOrderNotActivated_SkipsOrder() {
        insertPaymentOrder(firstUserId, "po-renewal", PaymentOrderType.SUBSCRIPTION_RENEWAL, PaymentOrderStatus.CREATED,
                null, LocalDateTime.of(2026, 4, 1, 9, 0), LocalDateTime.of(2026, 4, 1, 10, 0));

        assertThat(paymentOrderRepository.findActivePayableOrdersByUserId(firstUserId)).isEmpty();
    }

    @Test
    void findActivePayableOrdersByUserId_WhenRenewalOrderActivated_IncludesOrder() {
        insertPaymentOrder(firstUserId, "po-renewal-active", PaymentOrderType.SUBSCRIPTION_RENEWAL, PaymentOrderStatus.CREATED,
                LocalDateTime.of(2026, 4, 1, 9, 0), LocalDateTime.of(2026, 4, 1, 9, 0), LocalDateTime.of(2026, 4, 1, 10, 0));

        assertThat(paymentOrderRepository.findActivePayableOrdersByUserId(firstUserId))
                .extracting(PaymentOrder::getOrderNo)
                .containsExactly("po-renewal-active");
    }

    @Test
    void findExpirableOrders_WhenAwaitingConfirmationOrNotActivated_SkipsThem() {
        Long thirdUserId = insertUser("payment-order-user-c-" + UUID.randomUUID(), "payment-order-c@example.com");
        insertPaymentOrder(firstUserId, "po-created", PaymentOrderType.CREDIT_PURCHASE, PaymentOrderStatus.CREATED,
                LocalDateTime.of(2026, 4, 1, 8, 0), LocalDateTime.of(2026, 4, 1, 8, 0),
                LocalDateTime.of(2026, 4, 1, 8, 30));
        insertPaymentOrder(secondUserId, "po-pending", PaymentOrderType.CREDIT_PURCHASE, PaymentOrderStatus.PENDING_PROVIDER,
                LocalDateTime.of(2026, 4, 1, 8, 5), LocalDateTime.of(2026, 4, 1, 8, 5),
                LocalDateTime.of(2026, 4, 1, 8, 20));
        insertPaymentOrder(thirdUserId, "po-awaiting", PaymentOrderType.CREDIT_PURCHASE, PaymentOrderStatus.AWAITING_CONFIRMATION,
                LocalDateTime.of(2026, 4, 1, 8, 10), LocalDateTime.of(2026, 4, 1, 8, 10),
                LocalDateTime.of(2026, 4, 1, 8, 10));
        insertPaymentOrder(firstUserId, "po-not-activated", PaymentOrderType.SUBSCRIPTION_RENEWAL, PaymentOrderStatus.CREATED,
                null, LocalDateTime.of(2026, 4, 1, 8, 15), LocalDateTime.of(2026, 4, 1, 8, 5));

        assertThat(paymentOrderRepository.findExpirableOrders(LocalDateTime.of(2026, 4, 1, 9, 0), 10))
                .extracting(PaymentOrder::getOrderNo)
                .containsExactly("po-awaiting", "po-pending", "po-created");
    }

    @Test
    void findOrdersForPaymentStatusSync_WhenPendingOrdersOlderThanCutoff_ReturnsEligibleOrders() {
        Long thirdUserId = insertUser("payment-order-user-d-" + UUID.randomUUID(), "payment-order-d@example.com");
        insertPaymentOrder(firstUserId, "po-sync-pending", PaymentOrderType.CREDIT_PURCHASE, PaymentOrderStatus.PENDING_PROVIDER,
                LocalDateTime.of(2026, 4, 1, 8, 0), LocalDateTime.of(2026, 4, 1, 8, 0),
                LocalDateTime.of(2026, 4, 1, 9, 0));
        insertPaymentOrder(secondUserId, "po-sync-awaiting", PaymentOrderType.CREDIT_PURCHASE, PaymentOrderStatus.AWAITING_CONFIRMATION,
                LocalDateTime.of(2026, 4, 1, 8, 5), LocalDateTime.of(2026, 4, 1, 8, 5),
                LocalDateTime.of(2026, 4, 1, 9, 5));
        insertPaymentOrder(thirdUserId, "po-sync-recent", PaymentOrderType.CREDIT_PURCHASE, PaymentOrderStatus.PENDING_PROVIDER,
                LocalDateTime.of(2026, 4, 1, 10, 0), LocalDateTime.of(2026, 4, 1, 10, 0),
                LocalDateTime.of(2026, 4, 1, 11, 0));
        insertPaymentOrder(firstUserId, "po-sync-created", PaymentOrderType.CREDIT_PURCHASE, PaymentOrderStatus.CREATED,
                null, LocalDateTime.of(2026, 4, 1, 7, 0),
                LocalDateTime.of(2026, 4, 1, 8, 0));

        assertThat(paymentOrderRepository.findOrdersForPaymentStatusSync(LocalDateTime.of(2026, 4, 1, 9, 30), 10))
                .extracting(PaymentOrder::getOrderNo)
                .containsExactly("po-sync-pending", "po-sync-awaiting");
    }

    private Long insertUser(String username, String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, username, email, "hashed", "zh-CN", "Asia/Shanghai");
    }

    private void insertPaymentOrder(
            Long userId,
            String orderNo,
            PaymentOrderType orderType,
            PaymentOrderStatus status,
            LocalDateTime payableActivatedAt,
            LocalDateTime createdAt,
            LocalDateTime expiresAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO payment_order (
                    external_id,
                    order_no,
                    user_id,
                    order_type,
                    biz_ref_type,
                    biz_ref_id,
                    provider,
                    amount,
                    currency,
                    status,
                    idempotency_key,
                    provider_order_ref,
                    pricing_snapshot,
                    entitlement_snapshot,
                    payable_activated_at,
                    expires_at,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                orderNo,
                userId,
                orderType.name(),
                orderType == PaymentOrderType.SUBSCRIPTION_RENEWAL ? "SUBSCRIPTION_CONTRACT" : "CREDIT_PURCHASE_SKU",
                orderType == PaymentOrderType.SUBSCRIPTION_RENEWAL ? "contract-1" : "credits-basic",
                "mockpay",
                new BigDecimal("9.900000"),
                "USD",
                status.name(),
                orderNo + "-idem",
                orderNo,
                "{\"snapshotType\":\"PURCHASE\"}",
                "[]",
                payableActivatedAt,
                expiresAt,
                createdAt,
                createdAt
        );
    }

    private PaymentOrder paymentOrder(Long userId, String orderNo, String idempotencyKey) {
        return PaymentOrder.builder()
                .externalId(UUID.randomUUID())
                .orderNo(orderNo)
                .userId(userId)
                .orderType(PaymentOrderType.CREDIT_PURCHASE)
                .bizRefType("CREDIT_PURCHASE_SKU")
                .bizRefId("credits-basic")
                .provider("mockpay")
                .amount(new BigDecimal("9.900000"))
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .idempotencyKey(idempotencyKey)
                .providerOrderRef(orderNo)
                .pricingSnapshot("{\"snapshotType\":\"PURCHASE\"}")
                .entitlementSnapshot("[]")
                .payableActivatedAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .expiresAt(LocalDateTime.of(2026, 4, 2, 0, 0))
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = PaymentOrder.class)
    @EnableJpaRepositories(basePackageClasses = PaymentOrderRepository.class)
    static class TestBillingJpaApplication {
    }
}
