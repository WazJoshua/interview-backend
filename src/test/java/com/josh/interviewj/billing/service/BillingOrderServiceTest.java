package com.josh.interviewj.billing.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.dto.request.CreateBillingOrderRequest;
import com.josh.interviewj.billing.dto.response.UserBillingOrderResponse;
import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanEntitlementItem;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.provider.PaymentProviderRegistry;
import com.josh.interviewj.billing.repository.BillingPlanEntitlementItemRepository;
import com.josh.interviewj.billing.repository.BillingPlanRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.CreditPurchaseSkuRepository;
import com.josh.interviewj.billing.repository.CreditPurchaseSkuVersionRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import com.josh.interviewj.usage.service.CreditFormattingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingOrderServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BillingPlanRepository billingPlanRepository;

    @Mock
    private BillingPlanVersionRepository billingPlanVersionRepository;

    @Mock
    private BillingPlanEntitlementItemRepository entitlementItemRepository;

    @Mock
    private CreditPurchaseSkuRepository creditPurchaseSkuRepository;

    @Mock
    private CreditPurchaseSkuVersionRepository creditPurchaseSkuVersionRepository;

    @Mock
    private SubscriptionContractRepository subscriptionContractRepository;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private PaymentProviderRegistry paymentProviderRegistry;

    @Mock
    private InventoryReservationService inventoryReservationService;

    @Mock
    private PlatformTransactionManager txManager;

    @Mock
    private RuntimeSwitchService runtimeSwitchService;

    private BillingOrderService service;

    @BeforeEach
    void setUp() {
        BillingProperties properties = new BillingProperties();
        properties.getOrder().setDefaultExpireMinutes(30);
        // Stub txManager so TransactionTemplate.execute runs the callback directly
        lenient().when(txManager.getTransaction(any())).thenReturn(org.mockito.Mockito.mock(TransactionStatus.class));
        service = new BillingOrderService(
                Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC),
                userRepository,
                properties,
                new CreditFormattingService(),
                new BillingSnapshotCodec(JsonMapper.builder().build()),
                inventoryReservationService,
                billingPlanRepository,
                billingPlanVersionRepository,
                entitlementItemRepository,
                creditPurchaseSkuRepository,
                creditPurchaseSkuVersionRepository,
                subscriptionContractRepository,
                paymentOrderRepository,
                paymentProviderRegistry,
                runtimeSwitchService,
                txManager
        );
        lenient().when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(paymentOrderRepository.saveAndFlush(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(paymentProviderRegistry.requireAdapter("mockpay")).thenReturn(null);
        lenient().when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenReturn(List.of());
    }

    @Test
    void createOrder_SubscriptionPurchaseFreezesPricingAndEntitlementSnapshot() {
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user()));
        when(paymentOrderRepository.findByUserIdAndIdempotencyKey(101L, "idem-1")).thenReturn(Optional.empty());
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.empty());
        when(billingPlanRepository.findByPlanCode("plus")).thenReturn(Optional.of(plan()));
        when(billingPlanVersionRepository.findActiveSellableVersion(11L, LocalDateTime.of(2026, 4, 1, 0, 0)))
                .thenReturn(Optional.of(planVersion()));
        when(entitlementItemRepository.findByBillingPlanVersionIdOrderByBucketCodeAsc(21L))
                .thenReturn(List.of(entitlementItem()));

        UserBillingOrderResponse response = service.createOrder("josh", subscriptionOrderRequest());

        assertThat(response.getOrderType()).isEqualTo("SUBSCRIPTION_PURCHASE");
        assertThat(response.getBizRefId()).isEqualTo("plus");
        assertThat(response.getAmount()).isEqualTo("29.900000");
        assertThat(response.getPricingSnapshot()).containsEntry("planCode", "plus");
        assertThat(response.getPayableActivatedAt()).isEqualTo(OffsetDateTime.parse("2026-04-01T00:00:00Z"));
        assertThat(response.isReused()).isFalse();
        verify(paymentOrderRepository).saveAndFlush(any(PaymentOrder.class));
        verify(paymentOrderRepository).save(any(PaymentOrder.class));
    }

    @Test
    void createOrder_DuplicateIdempotencyKeyReturnsExistingOpenOrder() {
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user()));
        when(paymentOrderRepository.findByUserIdAndIdempotencyKey(101L, "idem-dup")).thenReturn(Optional.of(existingOrder()));

        UserBillingOrderResponse response = service.createOrder("josh", duplicateRequest());

        assertThat(response.getOrderNo()).isEqualTo("po_existing");
        verify(paymentOrderRepository).findByUserIdAndIdempotencyKey(101L, "idem-dup");
    }

    @Test
    void createOrder_WhenSameIntentBlockingOrderExists_ReusesExistingOrder() {
        PaymentOrder blockingOrder = existingOrder();
        blockingOrder.setIdempotencyKey("old-idem");
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user()));
        when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenReturn(List.of(blockingOrder));

        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setOrderType("SUBSCRIPTION_PURCHASE");
        request.setProvider("mockpay");
        request.setIdempotencyKey("new-idem");
        request.setPlanCode("plus");

        UserBillingOrderResponse response = service.createOrder("josh", request);

        assertThat(response.getOrderNo()).isEqualTo("po_existing");
        assertThat(response.isReused()).isTrue();
        verify(paymentOrderRepository, times(0)).save(any(PaymentOrder.class));
    }

    @Test
    void createOrder_WhenDifferentIntentBlockingOrderExists_ThrowsConflict() {
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user()));
        when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenReturn(List.of(existingOrder()));

        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setOrderType("SUBSCRIPTION_PURCHASE");
        request.setProvider("mockpay");
        request.setIdempotencyKey("idem-new");
        request.setPlanCode("pro");

        assertThatThrownBy(() -> service.createOrder("josh", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_004);
                });
    }

    @Test
    void createOrder_WhenRenewalOrderNotActivated_DoesNotBlockNewPurchase() {
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user()));
        when(paymentOrderRepository.findByUserIdAndIdempotencyKey(101L, "idem-2")).thenReturn(Optional.empty());
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.empty());
        when(billingPlanRepository.findByPlanCode("plus")).thenReturn(Optional.of(plan()));
        when(billingPlanVersionRepository.findActiveSellableVersion(11L, LocalDateTime.of(2026, 4, 1, 0, 0)))
                .thenReturn(Optional.of(planVersion()));
        when(entitlementItemRepository.findByBillingPlanVersionIdOrderByBucketCodeAsc(21L))
                .thenReturn(List.of(entitlementItem()));

        UserBillingOrderResponse response = service.createOrder("josh", renewalBypassedRequest());

        assertThat(response.getOrderNo()).isNotBlank();
        assertThat(response.isReused()).isFalse();
        assertThat(response.getPayableActivatedAt()).isEqualTo(OffsetDateTime.parse("2026-04-01T00:00:00Z"));
    }

    @Test
    void createOrder_WhenActivatedRenewalOrderExists_ThrowsConflict() {
        PaymentOrder renewalOrder = PaymentOrder.builder()
                .id(401L)
                .externalId(UUID.randomUUID())
                .orderNo("po_renewal")
                .userId(101L)
                .provider("mockpay")
                .orderType(PaymentOrderType.SUBSCRIPTION_RENEWAL)
                .bizRefType("SUBSCRIPTION_CONTRACT")
                .bizRefId("31")
                .amount(new BigDecimal("29.900000"))
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .idempotencyKey("renewal-idem")
                .pricingSnapshot("{\"planCode\":\"plus\"}")
                .payableActivatedAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .createdAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .expiresAt(LocalDateTime.of(2026, 4, 1, 0, 30))
                .build();

        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user()));
        when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenReturn(List.of(renewalOrder));

        assertThatThrownBy(() -> service.createOrder("josh", renewalBypassedRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_004);
    }

    @Test
    void createOrder_ReusedIdempotencyKeyWithDifferentRequest_ThrowsException() {
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user()));
        when(paymentOrderRepository.findByUserIdAndIdempotencyKey(101L, "idem-dup")).thenReturn(Optional.of(existingOrder()));

        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setOrderType("SUBSCRIPTION_PURCHASE");
        request.setProvider("mockpay");
        request.setIdempotencyKey("idem-dup");
        request.setPlanCode("pro");

        assertThatThrownBy(() -> service.createOrder("josh", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_004);
                });
    }

    @Test
    void createOrder_WhenSaveAndFlushHitsUniqueConstraint_FallsBackToIdempotencyKeyLookup() {
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user()));
        when(paymentOrderRepository.findByUserIdAndIdempotencyKey(101L, "idem-race")).thenReturn(Optional.empty());
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.empty());
        when(billingPlanRepository.findByPlanCode("plus")).thenReturn(Optional.of(plan()));
        when(billingPlanVersionRepository.findActiveSellableVersion(11L, LocalDateTime.of(2026, 4, 1, 0, 0)))
                .thenReturn(Optional.of(planVersion()));
        when(entitlementItemRepository.findByBillingPlanVersionIdOrderByBucketCodeAsc(21L))
                .thenReturn(List.of(entitlementItem()));
        when(paymentOrderRepository.saveAndFlush(any(PaymentOrder.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique constraint"));

        PaymentOrder winnerOrder = existingOrder();
        winnerOrder.setIdempotencyKey("idem-race");
        when(paymentOrderRepository.findByUserIdAndIdempotencyKey(101L, "idem-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerOrder));

        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setOrderType("SUBSCRIPTION_PURCHASE");
        request.setProvider("mockpay");
        request.setIdempotencyKey("idem-race");
        request.setPlanCode("plus");

        UserBillingOrderResponse response = service.createOrder("josh", request);

        assertThat(response.getOrderNo()).isEqualTo("po_existing");
        assertThat(response.isReused()).isTrue();
    }

    @Test
    void createOrder_WhenSaveAndFlushHitsUniqueConstraint_FallsBackToActiveOrderLookup() {
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user()));
        when(paymentOrderRepository.findByUserIdAndIdempotencyKey(101L, "idem-race2")).thenReturn(Optional.empty());
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.empty());
        when(billingPlanRepository.findByPlanCode("plus")).thenReturn(Optional.of(plan()));
        when(billingPlanVersionRepository.findActiveSellableVersion(11L, LocalDateTime.of(2026, 4, 1, 0, 0)))
                .thenReturn(Optional.of(planVersion()));
        when(entitlementItemRepository.findByBillingPlanVersionIdOrderByBucketCodeAsc(21L))
                .thenReturn(List.of(entitlementItem()));
        when(paymentOrderRepository.saveAndFlush(any(PaymentOrder.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique constraint"));

        PaymentOrder winnerOrder = existingOrder();
        winnerOrder.setIdempotencyKey("other-idem");
        when(paymentOrderRepository.findActivePayableOrdersByUserId(101L)).thenReturn(List.of(winnerOrder));

        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setOrderType("SUBSCRIPTION_PURCHASE");
        request.setProvider("mockpay");
        request.setIdempotencyKey("idem-race2");
        request.setPlanCode("plus");

        UserBillingOrderResponse response = service.createOrder("josh", request);

        assertThat(response.getOrderNo()).isEqualTo("po_existing");
        assertThat(response.isReused()).isTrue();
    }

    @Test
    void createOrder_WhenSaveAndFlushHitsUniqueConstraint_AndNoMatchFound_ThrowsConflict() {
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user()));
        when(paymentOrderRepository.findByUserIdAndIdempotencyKey(101L, "idem-race3")).thenReturn(Optional.empty());
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.empty());
        when(billingPlanRepository.findByPlanCode("plus")).thenReturn(Optional.of(plan()));
        when(billingPlanVersionRepository.findActiveSellableVersion(11L, LocalDateTime.of(2026, 4, 1, 0, 0)))
                .thenReturn(Optional.of(planVersion()));
        when(entitlementItemRepository.findByBillingPlanVersionIdOrderByBucketCodeAsc(21L))
                .thenReturn(List.of(entitlementItem()));
        when(paymentOrderRepository.saveAndFlush(any(PaymentOrder.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique constraint"));
        when(paymentOrderRepository.findActivePayableOrdersByUserId(101L)).thenReturn(List.of());

        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setOrderType("SUBSCRIPTION_PURCHASE");
        request.setProvider("mockpay");
        request.setIdempotencyKey("idem-race3");
        request.setPlanCode("plus");

        assertThatThrownBy(() -> service.createOrder("josh", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_004);
                });
    }

    @Test
    void createOrder_WhenProviderNotRegistered_ThrowsException() {
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user()));
        when(paymentProviderRegistry.requireAdapter("unknownpay"))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_001, "Payment provider is not registered"));

        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setOrderType("SUBSCRIPTION_PURCHASE");
        request.setProvider("unknownpay");
        request.setIdempotencyKey("idem-unknown");
        request.setPlanCode("plus");

        assertThatThrownBy(() -> service.createOrder("josh", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_001);
                });
    }

    @Test
    void createOrder_WhenPaymentDisabled_RejectsSubscriptionPurchaseBeforeBusinessFlow() {
        doThrow(new BusinessException(ErrorCode.PAYMENT_006, "Payment temporarily disabled"))
                .when(runtimeSwitchService)
                .requirePaymentEnabled();

        assertThatThrownBy(() -> service.createOrder("josh", subscriptionOrderRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_006);
                    assertThat(businessException.getMessage()).isEqualTo("Payment temporarily disabled");
                });
    }

    @Test
    void createOrder_WhenPaymentDisabled_RejectsCreditPurchase() {
        doThrow(new BusinessException(ErrorCode.PAYMENT_006, "Payment temporarily disabled"))
                .when(runtimeSwitchService)
                .requirePaymentEnabled();

        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setOrderType("CREDIT_PURCHASE");
        request.setProvider("mockpay");
        request.setIdempotencyKey("idem-credit-disabled");
        request.setPurchaseSkuCode("credits-basic");

        assertThatThrownBy(() -> service.createOrder("josh", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_006);
                    assertThat(businessException.getMessage()).isEqualTo("Payment temporarily disabled");
                });
    }

    private User user() {
        return User.builder()
                .id(101L)
                .externalId(UUID.randomUUID())
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build();
    }

    private BillingPlan plan() {
        return BillingPlan.builder()
                .id(11L)
                .externalId(UUID.randomUUID())
                .planCode("plus")
                .tierCode("plus")
                .displayName("Plus")
                .active(true)
                .build();
    }

    private BillingPlanVersion planVersion() {
        return BillingPlanVersion.builder()
                .id(21L)
                .externalId(UUID.randomUUID())
                .billingPlanId(11L)
                .versionNo(1)
                .billingCycle("MONTHLY")
                .amount(new BigDecimal("29.900000"))
                .currency("USD")
                .saleEnabled(true)
                .renewalEnabled(true)
                .effectiveFrom(LocalDateTime.of(2026, 4, 1, 0, 0))
                .build();
    }

    private BillingPlanEntitlementItem entitlementItem() {
        return BillingPlanEntitlementItem.builder()
                .billingPlanVersionId(21L)
                .bucketCode("RESUME_CREDITS")
                .grantAmountMicros(500_000L)
                .grantType("PERIODIC")
                .build();
    }

    private PaymentOrder existingOrder() {
        return PaymentOrder.builder()
                .id(301L)
                .externalId(UUID.randomUUID())
                .orderNo("po_existing")
                .userId(101L)
                .provider("mockpay")
                .orderType(PaymentOrderType.SUBSCRIPTION_PURCHASE)
                .bizRefType("BILLING_PLAN")
                .bizRefId("plus")
                .amount(new BigDecimal("29.900000"))
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .idempotencyKey("idem-dup")
                .pricingSnapshot("{\"planCode\":\"plus\"}")
                .payableActivatedAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .createdAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .expiresAt(LocalDateTime.of(2026, 4, 1, 0, 30))
                .build();
    }

    private CreateBillingOrderRequest subscriptionOrderRequest() {
        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setOrderType("SUBSCRIPTION_PURCHASE");
        request.setProvider("mockpay");
        request.setIdempotencyKey("idem-1");
        request.setPlanCode("plus");
        return request;
    }

    private CreateBillingOrderRequest duplicateRequest() {
        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setOrderType("SUBSCRIPTION_PURCHASE");
        request.setProvider("mockpay");
        request.setIdempotencyKey("idem-dup");
        request.setPlanCode("plus");
        return request;
    }

    private CreateBillingOrderRequest renewalBypassedRequest() {
        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setOrderType("SUBSCRIPTION_PURCHASE");
        request.setProvider("mockpay");
        request.setIdempotencyKey("idem-2");
        request.setPlanCode("plus");
        return request;
    }

}
