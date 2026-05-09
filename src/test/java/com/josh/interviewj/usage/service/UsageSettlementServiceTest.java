package com.josh.interviewj.usage.service;

import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.service.BillingUsageDebitResult;
import com.josh.interviewj.billing.service.BillingUsageDebitService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.usage.model.BillingUnit;
import com.josh.interviewj.usage.model.ChargeBucket;
import com.josh.interviewj.usage.model.ChargeStatus;
import com.josh.interviewj.usage.model.LlmModelPricingVersion;
import com.josh.interviewj.usage.model.LlmUsageChargeLedger;
import com.josh.interviewj.usage.model.LlmUsageCreditLedger;
import com.josh.interviewj.usage.model.LlmUsageEvent;
import com.josh.interviewj.usage.model.UsageCreditPolicyVersion;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.repository.LlmModelPricingVersionRepository;
import com.josh.interviewj.usage.repository.LlmUsageChargeLedgerRepository;
import com.josh.interviewj.usage.repository.LlmUsageCreditLedgerRepository;
import com.josh.interviewj.usage.repository.LlmUsageEventRepository;
import com.josh.interviewj.usage.repository.UsageCreditPolicyVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageSettlementServiceTest {

    @Mock
    private LlmUsageEventRepository usageEventRepository;

    @Mock
    private LlmModelPricingVersionRepository pricingVersionRepository;

    @Mock
    private LlmUsageChargeLedgerRepository chargeLedgerRepository;

    @Mock
    private UsageCreditPolicyVersionRepository creditPolicyVersionRepository;

    @Mock
    private LlmUsageCreditLedgerRepository creditLedgerRepository;

    @Mock
    private UserCreditPeriodService userCreditPeriodService;

    @Mock
    private UsageInternalPeriodService usageInternalPeriodService;

    @Mock
    private BillingUsageDebitService billingUsageDebitService;

    @Mock
    private UsageRejectionRecordingService usageRejectionRecordingService;

    private UsageSettlementService service;

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-01T03:04:05Z"), ZoneOffset.UTC);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        service = new UsageSettlementService(
                clock,
                objectMapper,
                new ChargeBucketResolver(),
                usageEventRepository,
                pricingVersionRepository,
                chargeLedgerRepository,
                creditPolicyVersionRepository,
                creditLedgerRepository,
                userCreditPeriodService,
                usageInternalPeriodService,
                billingUsageDebitService,
                usageRejectionRecordingService
        );

        lenient().when(usageEventRepository.save(any())).thenAnswer(invocation -> {
            LlmUsageEvent event = invocation.getArgument(0);
            event.setId(1001L);
            return event;
        });
        lenient().when(chargeLedgerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(creditLedgerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void settle_SuccessWithActivePricingAndPolicy_CreatesChargeableLedgersAndPeriods() {
        UsageOperationContext context = analysisSuccessContext();
        when(pricingVersionRepository.findActiveVersion(eq("default"), eq("qwen-plus"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activePricingVersion()));
        when(creditPolicyVersionRepository.findActiveVersion(eq("analysis"), eq("RESUME_CREDITS"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activeCreditPolicy()));
        when(billingUsageDebitService.debit(eq(101L), eq("RESUME_CREDITS"), eq(2750L), any(LocalDateTime.class), eq("1001")))
                .thenReturn(new BillingUsageDebitResult(
                        BillingEvent.builder().id(201L).build(),
                        2000L,
                        750L
                ));

        UsageSettlementResult result = service.settle(context);

        assertThat(result.duplicateIgnored()).isFalse();

        ArgumentCaptor<LlmUsageEvent> eventCaptor = ArgumentCaptor.forClass(LlmUsageEvent.class);
        verify(usageEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getChargeBucket()).isEqualTo(ChargeBucket.RESUME_CREDITS);
        assertThat(eventCaptor.getValue().getDedupeKey()).isEqualTo("analysis|RESUME_ANALYSIS_REPORT|report-ext-1|op-1|CHAT");
        assertThat(eventCaptor.getValue().getBusinessOperationId()).isEqualTo("biz-1");
        assertThat(eventCaptor.getValue().getExecutionDisposition()).isEqualTo("EXECUTED");

        ArgumentCaptor<LlmUsageChargeLedger> chargeCaptor = ArgumentCaptor.forClass(LlmUsageChargeLedger.class);
        verify(chargeLedgerRepository).save(chargeCaptor.capture());
        assertThat(chargeCaptor.getValue().getChargeStatus()).isEqualTo(ChargeStatus.CHARGEABLE);
        assertThat(chargeCaptor.getValue().getPromptTokenUnits()).isEqualTo(90L);
        assertThat(chargeCaptor.getValue().getCachedTokenUnits()).isEqualTo(10L);
        assertThat(chargeCaptor.getValue().getCompletionTokenUnits()).isEqualTo(40L);
        assertThat(chargeCaptor.getValue().getRequestUnits()).isEqualTo(1L);
        assertThat(chargeCaptor.getValue().getTotalAmount()).isEqualByComparingTo("0.675000");

        ArgumentCaptor<LlmUsageCreditLedger> creditCaptor = ArgumentCaptor.forClass(LlmUsageCreditLedger.class);
        verify(creditLedgerRepository).save(creditCaptor.capture());
        assertThat(creditCaptor.getValue().getChargeStatus()).isEqualTo(ChargeStatus.CHARGEABLE);
        assertThat(creditCaptor.getValue().getChargeBucket()).isEqualTo(ChargeBucket.RESUME_CREDITS);
        assertThat(creditCaptor.getValue().getChargedCreditsMicros()).isEqualTo(2750L);

        verify(userCreditPeriodService).incrementUsedCredits(
                101L,
                ChargeBucket.RESUME_CREDITS,
                LocalDateTime.of(2026, 4, 1, 0, 0),
                LocalDateTime.of(2026, 5, 1, 0, 0),
                2000L
        );
        verify(usageInternalPeriodService).increment(
                "default",
                "qwen-plus",
                UsageFamily.CHAT,
                "analysis",
                LocalDateTime.of(2026, 4, 1, 0, 0),
                LocalDateTime.of(2026, 5, 1, 0, 0),
                140L,
                10L,
                1L,
                140L,
                1L,
                new BigDecimal("0.675000"),
                "USD"
        );
    }

    @Test
    void settle_WhenClockZoneIsNonUtc_UsesUtcOccurredAtAndMonthlyWindow() {
        UsageSettlementService serviceWithShanghaiClock = new UsageSettlementService(
                Clock.fixed(Instant.parse("2026-03-31T16:30:00Z"), ZoneId.of("Asia/Shanghai")),
                objectMapper,
                new ChargeBucketResolver(),
                usageEventRepository,
                pricingVersionRepository,
                chargeLedgerRepository,
                creditPolicyVersionRepository,
                creditLedgerRepository,
                userCreditPeriodService,
                usageInternalPeriodService,
                billingUsageDebitService,
                usageRejectionRecordingService
        );

        UsageOperationContext context = analysisSuccessContext();
        when(pricingVersionRepository.findActiveVersion(eq("default"), eq("qwen-plus"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activePricingVersion()));
        when(creditPolicyVersionRepository.findActiveVersion(eq("analysis"), eq("RESUME_CREDITS"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activeCreditPolicy()));
        when(billingUsageDebitService.debit(eq(101L), eq("RESUME_CREDITS"), eq(2750L), any(LocalDateTime.class), eq("1001")))
                .thenReturn(new BillingUsageDebitResult(
                        BillingEvent.builder().id(202L).build(),
                        2750L,
                        0L
                ));

        serviceWithShanghaiClock.settle(context);

        // Verify occurredAt uses UTC business time (not clock timezone)
        ArgumentCaptor<LlmUsageEvent> eventCaptor = ArgumentCaptor.forClass(LlmUsageEvent.class);
        verify(usageEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getOccurredAt())
                .isEqualTo(LocalDateTime.of(2026, 3, 31, 16, 30));
        // Verify createdAt is NOT manually set (let @CreationTimestamp handle it)
        assertThat(eventCaptor.getValue().getCreatedAt()).isNull();

        verify(pricingVersionRepository).findActiveVersion(
                "default",
                "qwen-plus",
                "CHAT",
                LocalDateTime.of(2026, 3, 31, 16, 30)
        );
        verify(creditPolicyVersionRepository).findActiveVersion(
                "analysis",
                "RESUME_CREDITS",
                "CHAT",
                LocalDateTime.of(2026, 3, 31, 16, 30)
        );
        verify(userCreditPeriodService).incrementUsedCredits(
                101L,
                ChargeBucket.RESUME_CREDITS,
                LocalDateTime.of(2026, 3, 1, 0, 0),
                LocalDateTime.of(2026, 4, 1, 0, 0),
                2750L
        );
    }

    @Test
    void settle_WhenPricingMissing_CreatesPendingCostLedgerWithReason() {
        UsageOperationContext context = analysisSuccessContext();
        when(pricingVersionRepository.findActiveVersion(eq("default"), eq("qwen-plus"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(creditPolicyVersionRepository.findActiveVersion(eq("analysis"), eq("RESUME_CREDITS"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activeCreditPolicy()));
        when(billingUsageDebitService.debit(eq(101L), eq("RESUME_CREDITS"), eq(2750L), any(LocalDateTime.class), eq("1001")))
                .thenReturn(new BillingUsageDebitResult(
                        BillingEvent.builder().id(203L).build(),
                        2000L,
                        750L
                ));

        service.settle(context);

        ArgumentCaptor<LlmUsageChargeLedger> chargeCaptor = ArgumentCaptor.forClass(LlmUsageChargeLedger.class);
        verify(chargeLedgerRepository).save(chargeCaptor.capture());
        assertThat(chargeCaptor.getValue().getChargeStatus()).isEqualTo(ChargeStatus.PENDING);
        assertThat(chargeCaptor.getValue().getTotalAmount()).isEqualByComparingTo("0.000000");
        assertThat(chargeCaptor.getValue().getMetadata()).contains("PRICING_VERSION_MISSING");

        verify(usageInternalPeriodService).increment(
                "default",
                "qwen-plus",
                UsageFamily.CHAT,
                "analysis",
                LocalDateTime.of(2026, 4, 1, 0, 0),
                LocalDateTime.of(2026, 5, 1, 0, 0),
                140L,
                10L,
                1L,
                0L,
                0L,
                BigDecimal.ZERO.setScale(6),
                "USD"
        );
        verify(billingUsageDebitService).debit(eq(101L), eq("RESUME_CREDITS"), eq(2750L), any(LocalDateTime.class), eq("1001"));
    }

    @Test
    void settle_WhenCreditPolicyMissing_CreatesPendingCreditLedgerWithoutThrowing() {
        UsageOperationContext context = analysisSuccessContext();
        when(pricingVersionRepository.findActiveVersion(eq("default"), eq("qwen-plus"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activePricingVersion()));
        when(creditPolicyVersionRepository.findActiveVersion(eq("analysis"), eq("RESUME_CREDITS"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        service.settle(context);

        ArgumentCaptor<LlmUsageCreditLedger> creditCaptor = ArgumentCaptor.forClass(LlmUsageCreditLedger.class);
        verify(creditLedgerRepository).save(creditCaptor.capture());
        assertThat(creditCaptor.getValue().getChargeStatus()).isEqualTo(ChargeStatus.PENDING);
        assertThat(creditCaptor.getValue().getChargedCreditsMicros()).isNull();
        assertThat(creditCaptor.getValue().getCreditPolicyVersionId()).isNull();
        verify(userCreditPeriodService, never()).incrementUsedCredits(any(), any(), any(), any(), anyLong());
        verify(billingUsageDebitService, never()).debit(any(), any(), anyLong(), any(), any());
    }

    @Test
    void settle_WhenBusinessFailure_CreatesNonChargeableCreditLedgerAndPreservesCost() {
        UsageOperationContext context = analysisFailureContext(UsageBusinessOutcome.FAILED_NON_CHARGEABLE);
        when(pricingVersionRepository.findActiveVersion(eq("default"), eq("qwen-plus"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activePricingVersion()));
        when(creditPolicyVersionRepository.findActiveVersion(eq("analysis"), eq("RESUME_CREDITS"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activeCreditPolicy()));

        service.settle(context);

        ArgumentCaptor<LlmUsageCreditLedger> creditCaptor = ArgumentCaptor.forClass(LlmUsageCreditLedger.class);
        verify(creditLedgerRepository).save(creditCaptor.capture());
        assertThat(creditCaptor.getValue().getChargeStatus()).isEqualTo(ChargeStatus.NON_CHARGEABLE);
        assertThat(creditCaptor.getValue().getChargedCreditsMicros()).isNull();
        verify(userCreditPeriodService, never()).incrementUsedCredits(any(), any(), any(), any(), anyLong());
    }

    @Test
    void settle_WhenFallbackRecovered_CreatesNonChargeableCreditLedgerAndPreservesCost() {
        UsageOperationContext context = analysisFailureContext(UsageBusinessOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE);
        when(pricingVersionRepository.findActiveVersion(eq("default"), eq("qwen-plus"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activePricingVersion()));
        when(creditPolicyVersionRepository.findActiveVersion(eq("analysis"), eq("RESUME_CREDITS"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activeCreditPolicy()));

        service.settle(context);

        ArgumentCaptor<LlmUsageCreditLedger> creditCaptor = ArgumentCaptor.forClass(LlmUsageCreditLedger.class);
        verify(creditLedgerRepository).save(creditCaptor.capture());
        assertThat(creditCaptor.getValue().getChargeStatus()).isEqualTo(ChargeStatus.NON_CHARGEABLE);
        assertThat(creditCaptor.getValue().getChargedCreditsMicros()).isNull();
        verify(userCreditPeriodService, never()).incrementUsedCredits(any(), any(), any(), any(), anyLong());
    }

    @Test
    void settle_WhenDuplicateDedupeKey_IgnoresRepeatedWrite() {
        doThrow(new DataIntegrityViolationException("duplicate dedupe key"))
                .when(usageEventRepository)
                .save(any());

        UsageSettlementResult result = service.settle(analysisSuccessContext());

        assertThat(result.duplicateIgnored()).isTrue();
        verify(chargeLedgerRepository, never()).save(any());
        verify(creditLedgerRepository, never()).save(any());
        verify(userCreditPeriodService, never()).incrementUsedCredits(any(), any(), any(), any(), anyLong());
        verify(usageInternalPeriodService, never()).increment(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                any(BigDecimal.class),
                any()
        );
    }

    @Test
    void settle_WhenPurposeCannotMapToChargeBucket_ThrowsInsteadOfGuessing() {
        UsageOperationContext context = new UsageOperationContext(
                "unknown-purpose",
                "default",
                "qwen-plus",
                new ProviderUsage(UsageFamily.CHAT, 1L, 1L, 1L, 2L, 0L),
                "RESUME",
                "resume-ext-1",
                "op-3",
                101L,
                UsageBusinessOutcome.SUCCESS,
                null
        );

        assertThatThrownBy(() -> service.settle(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-purpose");
    }

    @Test
    void settle_WhenDebitRejected_RecordsUsageRejectionAndRethrows() {
        UsageOperationContext context = analysisSuccessContext();
        when(pricingVersionRepository.findActiveVersion(eq("default"), eq("qwen-plus"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activePricingVersion()));
        when(creditPolicyVersionRepository.findActiveVersion(eq("analysis"), eq("RESUME_CREDITS"), eq("CHAT"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(activeCreditPolicy()));
        when(billingUsageDebitService.debit(eq(101L), eq("RESUME_CREDITS"), eq(2750L), any(LocalDateTime.class), eq("1001")))
                .thenThrow(new BusinessException(ErrorCode.USER_BILLING_001, "Insufficient billing balance"));

        assertThatThrownBy(() -> service.settle(context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Insufficient billing balance");

        verify(usageRejectionRecordingService).recordDebitRejected(
                eq(context),
                eq("RESUME_CREDITS"),
                eq(com.josh.interviewj.usage.model.UsageRejectionReasonCode.INSUFFICIENT_CREDITS),
                eq("Insufficient billing balance"),
                any(LocalDateTime.class)
        );
        verify(creditLedgerRepository, never()).save(any());
    }

    private UsageOperationContext analysisSuccessContext() {
        return new UsageOperationContext(
                "analysis",
                "default",
                "qwen-plus",
                new ProviderUsage(UsageFamily.CHAT, 1L, 100L, 40L, 140L, 10L),
                "RESUME_ANALYSIS_REPORT",
                "report-ext-1",
                "op-1",
                "biz-1",
                101L,
                UsageBusinessOutcome.SUCCESS,
                null
        );
    }

    private UsageOperationContext analysisFailureContext(UsageBusinessOutcome outcome) {
        return new UsageOperationContext(
                "analysis",
                "default",
                "qwen-plus",
                new ProviderUsage(UsageFamily.CHAT, 1L, 100L, 40L, 140L, 10L),
                "RESUME_ANALYSIS_REPORT",
                "report-ext-1",
                "op-1",
                "biz-1",
                101L,
                outcome,
                "upstream failed"
        );
    }

    private LlmModelPricingVersion activePricingVersion() {
        return LlmModelPricingVersion.builder()
                .id(301L)
                .provider("default")
                .modelCode("qwen-plus")
                .usageFamily(UsageFamily.CHAT)
                .billingUnit(BillingUnit.TOKEN_AND_REQUEST)
                .promptTokenPrice(new BigDecimal("0.001000"))
                .completionTokenPrice(new BigDecimal("0.002000"))
                .cachedTokenPrice(new BigDecimal("0.000500"))
                .requestPrice(new BigDecimal("0.500000"))
                .currency("USD")
                .build();
    }

    private UsageCreditPolicyVersion activeCreditPolicy() {
        return UsageCreditPolicyVersion.builder()
                .id(401L)
                .purpose("analysis")
                .chargeBucket(ChargeBucket.RESUME_CREDITS)
                .usageFamily(UsageFamily.CHAT)
                .billingUnit(BillingUnit.TOKEN_AND_REQUEST)
                .promptTokenRatio(new BigDecimal("10"))
                .completionTokenRatio(new BigDecimal("20"))
                .cachedTokenRatio(new BigDecimal("5"))
                .requestRatio(new BigDecimal("1000"))
                .build();
    }
}
