package com.josh.interviewj.billing.service;

import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.dto.request.AdminReconciliationDecisionRequest;
import com.josh.interviewj.billing.model.BillingReconciliationCase;
import com.josh.interviewj.billing.repository.BillingReconciliationCaseRepository;
import com.josh.interviewj.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingReconciliationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AdminOperationLogService adminOperationLogService;

    @Mock
    private BillingReconciliationCaseRepository reconciliationCaseRepository;

    @Mock
    private BillingReconciliationResolutionExecutor reconciliationResolutionExecutor;

    private BillingReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new BillingReconciliationService(
                Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC),
                new BillingSnapshotCodec(JsonMapper.builder().build()),
                userRepository,
                adminOperationLogService,
                reconciliationCaseRepository,
                reconciliationResolutionExecutor
        );
    }

    @Test
    void resolveCase_WhenFulfillManually_ExecutesActionThenResolvesAndAudits() {
        BillingReconciliationCase reconciliationCase = BillingReconciliationCase.builder()
                .id(11L)
                .userId(101L)
                .paymentOrderId(21L)
                .status("OPEN")
                .caseType("PAYMENT_ORDER")
                .reasonCode("LATE_SUCCESS_AFTER_EXPIRY")
                .details("{}")
                .createdAt(LocalDateTime.of(2026, 4, 2, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 4, 2, 0, 0))
                .build();
        AdminReconciliationDecisionRequest request = new AdminReconciliationDecisionRequest();
        request.setResolutionCode("FULFILL_MANUALLY");
        request.setRequestId("req-1");
        request.setMetadata(Map.of("note", "manual"));
        when(reconciliationCaseRepository.findById(11L)).thenReturn(Optional.of(reconciliationCase));
        when(reconciliationCaseRepository.save(any(BillingReconciliationCase.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(101L)).thenReturn(Optional.of(User.builder()
                .id(101L)
                .externalId(UUID.randomUUID())
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build()));

        var response = service.resolveCase(999L, 11L, request);

        assertThat(response.getStatus()).isEqualTo("RESOLVED");
        assertThat(reconciliationCase.getResolutionCode()).isEqualTo("FULFILL_MANUALLY");
        InOrder inOrder = inOrder(reconciliationResolutionExecutor, reconciliationCaseRepository, adminOperationLogService);
        inOrder.verify(reconciliationResolutionExecutor).execute(eq(reconciliationCase), eq("FULFILL_MANUALLY"), eq(Map.of("note", "manual")));
        inOrder.verify(reconciliationCaseRepository).save(reconciliationCase);
        inOrder.verify(adminOperationLogService).recordUpdate(eq(999L), any(), eq("11"), eq("req-1"), any(), eq(reconciliationCase), any());
    }

    @Test
    void resolveCase_WhenAlreadyResolved_ThrowsConflict() {
        BillingReconciliationCase reconciliationCase = BillingReconciliationCase.builder()
                .id(11L)
                .status("RESOLVED")
                .build();
        AdminReconciliationDecisionRequest request = new AdminReconciliationDecisionRequest();
        request.setResolutionCode("CLOSE_NO_ACTION");
        when(reconciliationCaseRepository.findById(11L)).thenReturn(Optional.of(reconciliationCase));

        assertThatThrownBy(() -> service.resolveCase(999L, 11L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("ADMIN_BILLING_004");
        verify(reconciliationResolutionExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void resolveCase_WhenResolutionCodeInvalid_ThrowsValidationFailure() {
        BillingReconciliationCase reconciliationCase = BillingReconciliationCase.builder()
                .id(11L)
                .status("OPEN")
                .details("{}")
                .build();
        AdminReconciliationDecisionRequest request = new AdminReconciliationDecisionRequest();
        request.setResolutionCode("INVALID");
        when(reconciliationCaseRepository.findById(11L)).thenReturn(Optional.of(reconciliationCase));
        doThrow(new BusinessException("ADMIN_BILLING_004", "Unsupported reconciliation resolution code"))
                .when(reconciliationResolutionExecutor)
                .execute(reconciliationCase, "INVALID", null);

        assertThatThrownBy(() -> service.resolveCase(999L, 11L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("ADMIN_BILLING_004");
        verify(reconciliationCaseRepository, never()).save(any());
    }
}
