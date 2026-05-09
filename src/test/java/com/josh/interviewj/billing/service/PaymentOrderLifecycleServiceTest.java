package com.josh.interviewj.billing.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.model.BillingInventoryReservation;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.InventoryReservationStatus;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.repository.BillingInventoryReservationRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOrderLifecycleServiceTest {

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private BillingInventoryReservationRepository reservationRepository;

    @Mock
    private BillingPlanVersionRepository planVersionRepository;

    @Mock
    private InventoryReservationService inventoryReservationService;

    @Mock
    private UserRepository userRepository;

    private PaymentOrderLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new PaymentOrderLifecycleService(
                paymentOrderRepository,
                reservationRepository,
                planVersionRepository,
                inventoryReservationService,
                userRepository
        );
        lenient().when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void cancelOrder_WhenCurrentUserOwnsOrder_ReleasesMutexAndReservation() {
        PaymentOrder order = blockingOrder();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(User.builder()
                .id(101L)
                .externalId(UUID.randomUUID())
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build()));
        when(paymentOrderRepository.findByOrderNoAndUserId("po_123", 101L)).thenReturn(Optional.of(order));
        when(reservationRepository.findByPaymentOrderIdAndStatus(11L, InventoryReservationStatus.RESERVED))
                .thenReturn(Optional.of(BillingInventoryReservation.builder()
                        .paymentOrderId(11L)
                        .billingPlanVersionId(21L)
                        .status(InventoryReservationStatus.RESERVED)
                        .build()));
        when(planVersionRepository.findById(21L)).thenReturn(Optional.of(BillingPlanVersion.builder()
                .id(21L)
                .inventoryControlEnabledAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .build()));

        PaymentOrder canceledOrder = service.cancelCurrentUserOrder("josh", "po_123", "changed my mind");

        assertThat(canceledOrder.getStatus()).isEqualTo(PaymentOrderStatus.CANCELED);
        assertThat(canceledOrder.getPayableActivatedAt()).isNull();
        verify(inventoryReservationService).releaseForOrder(11L, 21L);
        verify(paymentOrderRepository).save(order);
    }

    @Test
    void processExpiredOrders_WhenRenewalOrderNotActivated_DoesNotExpireIt() {
        when(paymentOrderRepository.findExpirableOrders(LocalDateTime.of(2026, 4, 1, 12, 0), 10))
                .thenReturn(List.of());

        int processedCount = service.processExpiredOrders(LocalDateTime.of(2026, 4, 1, 12, 0), 10);

        assertThat(processedCount).isZero();
        verify(paymentOrderRepository, never()).save(any(PaymentOrder.class));
    }

    private PaymentOrder blockingOrder() {
        return PaymentOrder.builder()
                .id(11L)
                .externalId(UUID.randomUUID())
                .orderNo("po_123")
                .userId(101L)
                .orderType(PaymentOrderType.SUBSCRIPTION_PURCHASE)
                .bizRefType("BILLING_PLAN")
                .bizRefId("plus")
                .lockedPlanVersionId(21L)
                .provider("mockpay")
                .amount(new BigDecimal("29.900000"))
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .idempotencyKey("idem-1")
                .providerOrderRef("po_123")
                .pricingSnapshot("{\"snapshotType\":\"SUBSCRIPTION\"}")
                .entitlementSnapshot("[]")
                .payableActivatedAt(LocalDateTime.of(2026, 4, 1, 9, 0))
                .createdAt(LocalDateTime.of(2026, 4, 1, 9, 0))
                .expiresAt(LocalDateTime.of(2026, 4, 1, 9, 30))
                .build();
    }
}
