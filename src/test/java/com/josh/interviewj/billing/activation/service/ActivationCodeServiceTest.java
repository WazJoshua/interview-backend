package com.josh.interviewj.billing.activation.service;

import com.josh.interviewj.billing.activation.dto.request.CreateActivationCodeBatchRequest;
import com.josh.interviewj.billing.activation.dto.request.CreateActivationCodeRequest;
import com.josh.interviewj.billing.activation.dto.response.RedeemResultResponse;
import com.josh.interviewj.billing.activation.model.ActivationCode;
import com.josh.interviewj.billing.activation.model.ActivationCodeStatus;
import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import com.josh.interviewj.billing.activation.repository.ActivationCodeRepository;
import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.repository.BillingPlanRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import com.josh.interviewj.billing.service.BillingEventService;
import com.josh.interviewj.billing.service.CreditBalanceProjectionService;
import com.josh.interviewj.billing.service.SubscriptionLifecycleService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationCodeServiceTest {

    @Mock
    private ActivationCodeRepository activationCodeRepository;

    @Mock
    private ActivationCodeFormatService formatService;

    @Mock
    private SubscriptionLifecycleService subscriptionLifecycleService;

    @Mock
    private CreditBalanceProjectionService creditBalanceProjectionService;

    @Mock
    private BillingEventService billingEventService;

    @Mock
    private SubscriptionContractRepository subscriptionContractRepository;

    @Mock
    private BillingPlanVersionRepository billingPlanVersionRepository;

    @Mock
    private BillingPlanRepository billingPlanRepository;

    @Mock
    private RuntimeSwitchService runtimeSwitchService;

    private Clock clock;
    private ActivationCodeService sut;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-03T10:00:00Z"), ZoneOffset.UTC);
        sut = new ActivationCodeService(
                activationCodeRepository,
                formatService,
                subscriptionLifecycleService,
                creditBalanceProjectionService,
                billingEventService,
                subscriptionContractRepository,
                billingPlanVersionRepository,
                billingPlanRepository,
                runtimeSwitchService,
                clock
        );
    }

    @Test
    void generateCodeActivationCapabilityDisabledThrowsBillingActivation004() {
        doThrow(new BusinessException(ErrorCode.BILLING_ACTIVATION_004, "activation disabled"))
                .when(runtimeSwitchService)
                .requireActivationCodeEnabled();

        CreateActivationCodeRequest request = new CreateActivationCodeRequest();
        request.setCodeType(ActivationCodeType.CREDIT);
        request.setCreditAmountMicros(10_000_000L);

        assertThatThrownBy(() -> sut.generateCode(100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BILLING_ACTIVATION_004);
    }

    @Test
    void redeemRuntimeSwitchLoadFailureFailsClosedWithBillingActivation004() {
        doThrow(new BusinessException(
                ErrorCode.BILLING_ACTIVATION_004,
                "Activation code capability is temporarily unavailable"
        )).when(runtimeSwitchService).requireActivationCodeEnabled();

        assertThatThrownBy(() -> sut.redeem(1L, "SUBABCD1234"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BILLING_ACTIVATION_004);
    }

    @Test
    void generateCodeSubscriptionCreatesCodeWithCorrectFields() {
        when(formatService.generate(ActivationCodeType.SUBSCRIPTION)).thenReturn("SUBABCD1234");
        when(activationCodeRepository.findByCode("SUBABCD1234")).thenReturn(Optional.empty());
        when(billingPlanVersionRepository.findById(42L)).thenReturn(Optional.of(BillingPlanVersion.builder().id(42L).build()));
        when(activationCodeRepository.save(any(ActivationCode.class)))
                .thenAnswer(invocation -> {
                    ActivationCode code = invocation.getArgument(0);
                    code.setId(1L);
                    return code;
                });

        CreateActivationCodeRequest request = new CreateActivationCodeRequest();
        request.setCodeType(ActivationCodeType.SUBSCRIPTION);
        request.setBillingPlanVersionId(42L);
        request.setSubscriptionDurationDays(30);
        request.setNote("test");

        ActivationCode result = sut.generateCode(100L, request);

        assertThat(result.getCode()).isEqualTo("SUBABCD1234");
        assertThat(result.getCodeType()).isEqualTo(ActivationCodeType.SUBSCRIPTION);
        assertThat(result.getStatus()).isEqualTo(ActivationCodeStatus.UNUSED);
        assertThat(result.getCreatedByUserId()).isEqualTo(100L);
    }

    @Test
    void generateCodeSubscriptionPlanVersionNotFoundThrowsUserBilling003() {
        when(billingPlanVersionRepository.findById(999L)).thenReturn(Optional.empty());

        CreateActivationCodeRequest request = new CreateActivationCodeRequest();
        request.setCodeType(ActivationCodeType.SUBSCRIPTION);
        request.setBillingPlanVersionId(999L);
        request.setSubscriptionDurationDays(30);

        assertThatThrownBy(() -> sut.generateCode(100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("USER_BILLING_003");
    }

    @Test
    void generateCodeCreditWithSubscriptionFieldsThrowsAdminBilling001() {
        CreateActivationCodeRequest request = new CreateActivationCodeRequest();
        request.setCodeType(ActivationCodeType.CREDIT);
        request.setCreditAmountMicros(10_000_000L);
        request.setSubscriptionDurationDays(30);

        assertThatThrownBy(() -> sut.generateCode(100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("ADMIN_BILLING_001");
    }

    @Test
    void generateCodeRetriesWhenSaveHitsUniqueConstraint() {
        when(formatService.generate(ActivationCodeType.CREDIT)).thenReturn("CRDAAAAAAAA", "CRDBBBBBBBB");
        when(activationCodeRepository.findByCode("CRDAAAAAAAA")).thenReturn(Optional.empty());
        when(activationCodeRepository.findByCode("CRDBBBBBBBB")).thenReturn(Optional.empty());
        when(activationCodeRepository.save(any(ActivationCode.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"uk_activation_code_code\""))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateActivationCodeRequest request = new CreateActivationCodeRequest();
        request.setCodeType(ActivationCodeType.CREDIT);
        request.setCreditAmountMicros(10_000_000L);

        ActivationCode result = sut.generateCode(100L, request);

        assertThat(result.getCode()).isEqualTo("CRDBBBBBBBB");
    }

    @Test
    void generateCodeUniqueRetryExhaustedThrowsAdminBilling002() {
        when(formatService.generate(ActivationCodeType.CREDIT)).thenReturn("CRDXXXXXXXX");
        when(activationCodeRepository.findByCode("CRDXXXXXXXX")).thenReturn(Optional.empty());
        when(activationCodeRepository.save(any(ActivationCode.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"uk_activation_code_code\""));

        CreateActivationCodeRequest request = new CreateActivationCodeRequest();
        request.setCodeType(ActivationCodeType.CREDIT);
        request.setCreditAmountMicros(10_000_000L);

        assertThatThrownBy(() -> sut.generateCode(100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("ADMIN_BILLING_002");
    }

    @Test
    void generateBatchCreatesMultipleCodesWithSameBatchId() {
        when(formatService.generate(any())).thenReturn("CRDAAAAAAAA", "CRDBBBBBBBB", "CRDCCCCCCCC");
        when(activationCodeRepository.findByCode(any())).thenReturn(Optional.empty());
        when(activationCodeRepository.save(any(ActivationCode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateActivationCodeBatchRequest request = new CreateActivationCodeBatchRequest();
        request.setCount(3);
        request.setCodeType(ActivationCodeType.CREDIT);
        request.setCreditAmountMicros(10_000_000_000L);

        List<ActivationCode> result = sut.generateBatch(100L, request);

        assertThat(result).hasSize(3);
        assertThat(result.stream().map(ActivationCode::getBatchId).distinct()).hasSize(1);
    }

    @Test
    void generateBatchSubscriptionPlanVersionNotFoundThrowsUserBilling003() {
        when(billingPlanVersionRepository.findById(404L)).thenReturn(Optional.empty());

        CreateActivationCodeBatchRequest request = new CreateActivationCodeBatchRequest();
        request.setCount(2);
        request.setCodeType(ActivationCodeType.SUBSCRIPTION);
        request.setBillingPlanVersionId(404L);
        request.setSubscriptionDurationDays(30);

        assertThatThrownBy(() -> sut.generateBatch(100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("USER_BILLING_003");
    }

    @Test
    void redeemSubscriptionWithoutExistingSubscriptionSucceeds() {
        ActivationCode code = ActivationCode.builder()
                .id(1L)
                .code("SUBABCD1234")
                .codeType(ActivationCodeType.SUBSCRIPTION)
                .status(ActivationCodeStatus.UNUSED)
                .billingPlanVersionId(42L)
                .subscriptionDurationDays(30)
                .build();
        when(formatService.normalize("SUB-ABCD-1234")).thenReturn("SUBABCD1234");
        when(activationCodeRepository.findByCodeForUpdate("SUBABCD1234")).thenReturn(Optional.of(code));
        when(subscriptionContractRepository.findOpenContractByUserId(1L)).thenReturn(Optional.empty());

        BillingPlanVersion version = BillingPlanVersion.builder().id(42L).billingPlanId(10L).build();
        when(billingPlanVersionRepository.findById(42L)).thenReturn(Optional.of(version));
        BillingPlan plan = BillingPlan.builder().id(10L).tierRank(2).displayName("Pro").build();
        when(billingPlanRepository.findById(10L)).thenReturn(Optional.of(plan));

        SubscriptionContract contract = SubscriptionContract.builder()
                .id(50L)
                .currentPeriodEnd(LocalDateTime.of(2026, 5, 3, 10, 0))
                .build();
        when(subscriptionLifecycleService.applyActivationCodeSubscription(1L, 42L, 30, "1")).thenReturn(contract);
        when(activationCodeRepository.save(any(ActivationCode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingEventService.createOrGet(eq(1L), eq(BillingEventType.ACTIVATION_CODE_SUBSCRIPTION_GRANTED), eq("ACTIVATION_CODE"),
                eq("1"), eq("ACTIVATION_CODE|1|subscription-granted"), anyLong(), isNull(), any(), any()))
                .thenReturn(BillingEvent.builder().id(999L).build());

        RedeemResultResponse result = sut.redeem(1L, "SUB-ABCD-1234");

        assertThat(result.getCodeType()).isEqualTo(ActivationCodeType.SUBSCRIPTION);
        assertThat(result.getGrantedPlanName()).isEqualTo("Pro");
        assertThat(result.getGrantedDurationDays()).isEqualTo(30);
        assertThat(code.getStatus()).isEqualTo(ActivationCodeStatus.REDEEMED);
        assertThat(code.getRedeemedByUserId()).isEqualTo(1L);
        assertThat(code.getBillingEventId()).isEqualTo(999L);
    }

    @Test
    void redeemSubscriptionExistingSameOrHigherTierThrowsBillingActivation002() {
        ActivationCode code = ActivationCode.builder()
                .id(1L)
                .code("SUBABCD1234")
                .codeType(ActivationCodeType.SUBSCRIPTION)
                .status(ActivationCodeStatus.UNUSED)
                .billingPlanVersionId(42L)
                .subscriptionDurationDays(30)
                .build();
        when(formatService.normalize("SUBABCD1234")).thenReturn("SUBABCD1234");
        when(activationCodeRepository.findByCodeForUpdate("SUBABCD1234")).thenReturn(Optional.of(code));

        SubscriptionContract existing = SubscriptionContract.builder()
                .id(10L)
                .billingPlanId(5L)
                .status(SubscriptionContractStatus.ACTIVE)
                .build();
        when(subscriptionContractRepository.findOpenContractByUserId(1L)).thenReturn(Optional.of(existing));
        BillingPlan currentPlan = BillingPlan.builder().id(5L).tierRank(3).build();
        when(billingPlanRepository.findById(5L)).thenReturn(Optional.of(currentPlan));

        BillingPlanVersion newVersion = BillingPlanVersion.builder().id(42L).billingPlanId(10L).build();
        when(billingPlanVersionRepository.findById(42L)).thenReturn(Optional.of(newVersion));
        BillingPlan newPlan = BillingPlan.builder().id(10L).tierRank(2).build();
        when(billingPlanRepository.findById(10L)).thenReturn(Optional.of(newPlan));

        assertThatThrownBy(() -> sut.redeem(1L, "SUBABCD1234"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("BILLING_ACTIVATION_002");
    }

    @Test
    void redeemSubscriptionExistingLowerTierCancelsCurrentContractBeforeGrant() {
        ActivationCode code = ActivationCode.builder()
                .id(1L)
                .code("SUBABCD1234")
                .codeType(ActivationCodeType.SUBSCRIPTION)
                .status(ActivationCodeStatus.UNUSED)
                .billingPlanVersionId(42L)
                .subscriptionDurationDays(30)
                .build();
        when(formatService.normalize("SUBABCD1234")).thenReturn("SUBABCD1234");
        when(activationCodeRepository.findByCodeForUpdate("SUBABCD1234")).thenReturn(Optional.of(code));

        SubscriptionContract existing = SubscriptionContract.builder()
                .id(10L)
                .billingPlanId(5L)
                .status(SubscriptionContractStatus.ACTIVE)
                .build();
        when(subscriptionContractRepository.findOpenContractByUserId(1L)).thenReturn(Optional.of(existing));
        when(billingPlanRepository.findById(5L)).thenReturn(Optional.of(BillingPlan.builder().id(5L).tierRank(1).build()));
        when(billingPlanVersionRepository.findById(42L)).thenReturn(Optional.of(BillingPlanVersion.builder().id(42L).billingPlanId(10L).build()));
        when(billingPlanRepository.findById(10L)).thenReturn(Optional.of(BillingPlan.builder()
                .id(10L)
                .tierRank(2)
                .displayName("Pro")
                .build()));
        when(subscriptionLifecycleService.applyActivationCodeSubscription(1L, 42L, 30, "1"))
                .thenReturn(SubscriptionContract.builder()
                        .id(50L)
                        .currentPeriodEnd(LocalDateTime.of(2026, 5, 3, 10, 0))
                        .build());
        when(billingEventService.createOrGet(eq(1L), eq(BillingEventType.ACTIVATION_CODE_SUBSCRIPTION_GRANTED), eq("ACTIVATION_CODE"),
                eq("1"), eq("ACTIVATION_CODE|1|subscription-granted"), anyLong(), isNull(), any(), any()))
                .thenReturn(BillingEvent.builder().id(999L).build());
        when(activationCodeRepository.save(any(ActivationCode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RedeemResultResponse result = sut.redeem(1L, "SUBABCD1234");

        assertThat(result.getGrantedPlanName()).isEqualTo("Pro");
        assertThat(existing.getStatus()).isEqualTo(SubscriptionContractStatus.CANCELED);
        verify(subscriptionContractRepository).save(existing);
    }

    @Test
    void redeemCreditGrantsCreditsSuccessfully() {
        ActivationCode code = ActivationCode.builder()
                .id(2L)
                .code("CRDXYZ98765")
                .codeType(ActivationCodeType.CREDIT)
                .status(ActivationCodeStatus.UNUSED)
                .creditAmountMicros(10_000_000_000L)
                .build();
        when(formatService.normalize("CRD-XYZ9-8765")).thenReturn("CRDXYZ98765");
        when(activationCodeRepository.findByCodeForUpdate("CRDXYZ98765")).thenReturn(Optional.of(code));
        BillingEvent mockEvent = BillingEvent.builder().id(999L).build();
        when(billingEventService.createOrGet(any(), any(), any(), any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(mockEvent);
        when(activationCodeRepository.save(any(ActivationCode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RedeemResultResponse result = sut.redeem(1L, "CRD-XYZ9-8765");

        assertThat(result.getCodeType()).isEqualTo(ActivationCodeType.CREDIT);
        assertThat(result.getGrantedCredits()).isEqualTo(10_000_000_000L);
        verify(creditBalanceProjectionService).grantPurchasedCredits(eq(1L), eq(mockEvent), eq(10_000_000_000L), isNull(), any());
    }

    @Test
    void redeemAlreadyRedeemedThrowsBillingActivation003() {
        ActivationCode code = ActivationCode.builder()
                .id(1L)
                .code("SUBABCD1234")
                .status(ActivationCodeStatus.REDEEMED)
                .build();
        when(formatService.normalize("SUBABCD1234")).thenReturn("SUBABCD1234");
        when(activationCodeRepository.findByCodeForUpdate("SUBABCD1234")).thenReturn(Optional.of(code));

        assertThatThrownBy(() -> sut.redeem(1L, "SUBABCD1234"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("BILLING_ACTIVATION_003");
    }

    @Test
    void redeemCodeNotFoundThrowsBillingActivation001() {
        when(formatService.normalize("INVALID")).thenReturn("INVALID");
        when(activationCodeRepository.findByCodeForUpdate("INVALID")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.redeem(1L, "INVALID"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("BILLING_ACTIVATION_001");
    }

    @Test
    void redeemExpiredCodeThrowsBillingActivation003() {
        ActivationCode code = ActivationCode.builder()
                .id(1L)
                .code("SUBABCD1234")
                .status(ActivationCodeStatus.UNUSED)
                .expiresAt(LocalDateTime.of(2026, 4, 3, 9, 59))
                .build();
        when(formatService.normalize("SUBABCD1234")).thenReturn("SUBABCD1234");
        when(activationCodeRepository.findByCodeForUpdate("SUBABCD1234")).thenReturn(Optional.of(code));

        assertThatThrownBy(() -> sut.redeem(1L, "SUBABCD1234"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("BILLING_ACTIVATION_003");
    }

    @Test
    void voidCodeUnusedCodeSetsStatusToVoided() {
        ActivationCode code = ActivationCode.builder()
                .id(1L)
                .status(ActivationCodeStatus.UNUSED)
                .build();
        when(activationCodeRepository.findById(1L)).thenReturn(Optional.of(code));
        when(activationCodeRepository.save(any(ActivationCode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        sut.voidCode(1L);

        assertThat(code.getStatus()).isEqualTo(ActivationCodeStatus.VOIDED);
    }

    @Test
    void voidCodeAlreadyRedeemedThrowsBillingActivation003() {
        ActivationCode code = ActivationCode.builder()
                .id(1L)
                .status(ActivationCodeStatus.REDEEMED)
                .build();
        when(activationCodeRepository.findById(1L)).thenReturn(Optional.of(code));

        assertThatThrownBy(() -> sut.voidCode(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("BILLING_ACTIVATION_003");
    }

    @Test
    void generateCode_WithExpiresAt_PersistsUtcLocalTime() {
        when(formatService.generate(ActivationCodeType.CREDIT)).thenReturn("CRD12345678");
        when(activationCodeRepository.findByCode("CRD12345678")).thenReturn(Optional.empty());
        when(activationCodeRepository.save(any(ActivationCode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateActivationCodeRequest request = new CreateActivationCodeRequest();
        request.setCodeType(ActivationCodeType.CREDIT);
        request.setCreditAmountMicros(10_000_000L);
        request.setExpiresAt(OffsetDateTime.parse("2026-04-04T18:30:00+08:00"));

        ActivationCode result = sut.generateCode(100L, request);

        assertThat(result.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 4, 4, 10, 30));
    }

}
