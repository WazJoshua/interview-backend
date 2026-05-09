package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.BillingInventoryReservation;
import com.josh.interviewj.billing.model.BillingPlanInventory;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.InventoryConfirmationResult;
import com.josh.interviewj.billing.model.InventoryReservationStatus;
import com.josh.interviewj.billing.repository.BillingInventoryReservationRepository;
import com.josh.interviewj.billing.repository.BillingPlanInventoryRepository;
import com.josh.interviewj.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for InventoryReservationService idempotency scenarios.
 */
@ExtendWith(MockitoExtension.class)
class InventoryReservationServiceTest {

    @Mock
    private BillingPlanInventoryRepository inventoryRepository;

    @Mock
    private BillingInventoryReservationRepository reservationRepository;

    @Mock
    private BillingEventService billingEventService;

    @Mock
    private BillingSnapshotCodec billingSnapshotCodec;

    private InventoryReservationService service;

    @BeforeEach
    void setUp() {
        service = new InventoryReservationService(
                Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC),
                inventoryRepository,
                reservationRepository,
                billingEventService,
                billingSnapshotCodec
        );
    }

    // ==================== Idempotency Tests ====================

    @Test
    void confirmForOrder_DuplicateSuccessWebhook_ReturnsConfirmedWithoutError() {
        // Given: A reservation that is already CONFIRMED (first webhook already processed)
        BillingInventoryReservation confirmedReservation = BillingInventoryReservation.builder()
                .id(1L)
                .paymentOrderId(100L)
                .billingPlanVersionId(21L)
                .status(InventoryReservationStatus.CONFIRMED)
                .build();

        BillingPlanVersion version = planVersionWithInventoryControl(LocalDateTime.of(2026, 4, 1, 0, 0));
        LocalDateTime orderCreatedAt = LocalDateTime.of(2026, 4, 1, 12, 0); // after cutoff

        // When: confirmIfReserved returns 0 (no RESERVED to update)
        when(reservationRepository.confirmIfReserved(100L)).thenReturn(0);
        // And: findByPaymentOrderId returns the CONFIRMED reservation
        when(reservationRepository.findByPaymentOrderId(100L)).thenReturn(Optional.of(confirmedReservation));

        // Then: Should return CONFIRMED (idempotent)
        InventoryConfirmationResult result = service.confirmForOrder(
                100L, 21L, version, orderCreatedAt, 1L, "po_test"
        );

        assertThat(result).isEqualTo(InventoryConfirmationResult.CONFIRMED);

        // And: No reconciliation event should be created
        verify(billingEventService, never()).createOrGet(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong(), any(), any(), anyMap()
        );
    }

    @Test
    void confirmForOrder_ReservationReleasedButPaymentSucceeded_ReturnsRequiresReconciliation() {
        // Given: A reservation that is RELEASED (order was canceled/expired)
        BillingInventoryReservation releasedReservation = BillingInventoryReservation.builder()
                .id(1L)
                .paymentOrderId(100L)
                .billingPlanVersionId(21L)
                .status(InventoryReservationStatus.RELEASED)
                .build();

        BillingPlanVersion version = planVersionWithInventoryControl(LocalDateTime.of(2026, 4, 1, 0, 0));
        LocalDateTime orderCreatedAt = LocalDateTime.of(2026, 4, 1, 12, 0); // after cutoff

        // When: confirmIfReserved returns 0 (no RESERVED to update)
        when(reservationRepository.confirmIfReserved(100L)).thenReturn(0);
        // And: findByPaymentOrderId returns the RELEASED reservation
        when(reservationRepository.findByPaymentOrderId(100L)).thenReturn(Optional.of(releasedReservation));
        // And: billingEventService returns a mock event
        when(billingEventService.createOrGet(anyLong(), any(), anyString(), anyString(), anyString(), anyLong(), any(), any(), anyMap()))
                .thenReturn(BillingEvent.builder().id(999L).build());

        // Then: Should return REQUIRES_RECONCILIATION
        InventoryConfirmationResult result = service.confirmForOrder(
                100L, 21L, version, orderCreatedAt, 1L, "po_test"
        );

        assertThat(result).isEqualTo(InventoryConfirmationResult.REQUIRES_RECONCILIATION);

        // And: A reconciliation event should be created
        verify(billingEventService).createOrGet(
                eq(1L),
                eq(BillingEventType.POST_CUTOVER_ORDER_WITHOUT_RESERVATION),
                eq("INVENTORY_RECONCILIATION"),
                eq("100"),
                anyString(),
                eq(0L),
                any(),
                any(),
                anyMap()
        );
    }

    @Test
    void confirmForOrder_FirstWebhook_ConfirmsSuccessfully() {
        // Given: A reservation in RESERVED state
        BillingPlanVersion version = planVersionWithInventoryControl(LocalDateTime.of(2026, 4, 1, 0, 0));
        LocalDateTime orderCreatedAt = LocalDateTime.of(2026, 4, 1, 12, 0); // after cutoff

        // When: confirmIfReserved returns 1 (successfully updated RESERVED -> CONFIRMED)
        when(reservationRepository.confirmIfReserved(100L)).thenReturn(1);
        // And: confirmOneReservation succeeds
        when(inventoryRepository.confirmOneReservation(21L)).thenReturn(1);

        // Then: Should return CONFIRMED
        InventoryConfirmationResult result = service.confirmForOrder(
                100L, 21L, version, orderCreatedAt, 1L, "po_test"
        );

        assertThat(result).isEqualTo(InventoryConfirmationResult.CONFIRMED);

        // And: Inventory should be confirmed
        verify(inventoryRepository).confirmOneReservation(21L);
    }

    @Test
    void confirmForOrder_LegacyOrderWithoutReservation_ReturnsLegacyAllowed() {
        // Given: Order created before inventory_control_enabled_at (legacy)
        BillingPlanVersion version = planVersionWithInventoryControl(LocalDateTime.of(2026, 4, 1, 0, 0));
        LocalDateTime orderCreatedAt = LocalDateTime.of(2026, 3, 15, 12, 0); // before cutoff

        // When: confirmIfReserved returns 0 (no reservation)
        when(reservationRepository.confirmIfReserved(100L)).thenReturn(0);
        // And: findByPaymentOrderId returns empty (no reservation record)
        when(reservationRepository.findByPaymentOrderId(100L)).thenReturn(Optional.empty());
        // And: billingEventService returns a mock event
        when(billingEventService.createOrGet(anyLong(), any(), anyString(), anyString(), anyString(), anyLong(), any(), any(), anyMap()))
                .thenReturn(BillingEvent.builder().id(999L).build());

        // Then: Should return LEGACY_ALLOWED
        InventoryConfirmationResult result = service.confirmForOrder(
                100L, 21L, version, orderCreatedAt, 1L, "po_test"
        );

        assertThat(result).isEqualTo(InventoryConfirmationResult.LEGACY_ALLOWED);

        // And: A legacy reconciliation event should be created
        verify(billingEventService).createOrGet(
                eq(1L),
                eq(BillingEventType.LEGACY_ORDER_WITHOUT_RESERVATION),
                eq("INVENTORY_RECONCILIATION"),
                eq("100"),
                anyString(),
                eq(0L),
                any(),
                any(),
                anyMap()
        );
    }

    @Test
    void confirmForOrder_PostCutoverOrderWithoutReservation_ReturnsRequiresReconciliation() {
        // Given: Order created after inventory_control_enabled_at but no reservation exists
        BillingPlanVersion version = planVersionWithInventoryControl(LocalDateTime.of(2026, 4, 1, 0, 0));
        LocalDateTime orderCreatedAt = LocalDateTime.of(2026, 4, 1, 12, 0); // after cutoff

        // When: confirmIfReserved returns 0 (no reservation)
        when(reservationRepository.confirmIfReserved(100L)).thenReturn(0);
        // And: findByPaymentOrderId returns empty (no reservation record at all)
        when(reservationRepository.findByPaymentOrderId(100L)).thenReturn(Optional.empty());
        // And: billingEventService returns a mock event
        when(billingEventService.createOrGet(anyLong(), any(), anyString(), anyString(), anyString(), anyLong(), any(), any(), anyMap()))
                .thenReturn(BillingEvent.builder().id(999L).build());

        // Then: Should return REQUIRES_RECONCILIATION
        InventoryConfirmationResult result = service.confirmForOrder(
                100L, 21L, version, orderCreatedAt, 1L, "po_test"
        );

        assertThat(result).isEqualTo(InventoryConfirmationResult.REQUIRES_RECONCILIATION);

        // And: A post-cutover reconciliation event should be created
        verify(billingEventService).createOrGet(
                eq(1L),
                eq(BillingEventType.POST_CUTOVER_ORDER_WITHOUT_RESERVATION),
                eq("INVENTORY_RECONCILIATION"),
                eq("100"),
                anyString(),
                eq(0L),
                any(),
                any(),
                anyMap()
        );
    }

    // ==================== Helper Methods ====================

    private BillingPlanVersion planVersionWithInventoryControl(LocalDateTime inventoryControlEnabledAt) {
        return BillingPlanVersion.builder()
                .id(21L)
                .inventoryControlEnabledAt(inventoryControlEnabledAt)
                .build();
    }
}