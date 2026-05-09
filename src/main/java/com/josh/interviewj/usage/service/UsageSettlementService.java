package com.josh.interviewj.usage.service;

import com.josh.interviewj.llm.core.ProviderUsage;
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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UsageSettlementService {

    private static final String DEFAULT_CURRENCY = "USD";
    private static final String PRICING_VERSION_MISSING = "PRICING_VERSION_MISSING";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ChargeBucketResolver chargeBucketResolver;
    private final LlmUsageEventRepository usageEventRepository;
    private final LlmModelPricingVersionRepository pricingVersionRepository;
    private final LlmUsageChargeLedgerRepository chargeLedgerRepository;
    private final UsageCreditPolicyVersionRepository creditPolicyVersionRepository;
    private final LlmUsageCreditLedgerRepository creditLedgerRepository;
    private final UserCreditPeriodService userCreditPeriodService;
    private final UsageInternalPeriodService usageInternalPeriodService;
    private final BillingUsageDebitService billingUsageDebitService;
    private final UsageRejectionRecordingService usageRejectionRecordingService;

    public UsageSettlementResult settle(UsageOperationContext context) {
        ChargeBucket chargeBucket = chargeBucketResolver.resolve(context.purpose());
        LocalDateTime occurredAt = nowUtc();

        LlmUsageEvent event = buildEvent(context, chargeBucket, occurredAt);
        try {
            event = usageEventRepository.save(event);
        } catch (DataIntegrityViolationException exception) {
            return UsageSettlementResult.ignoredDuplicate();
        }

        Optional<LlmModelPricingVersion> pricingVersion = context.modelId() == null
                ? pricingVersionRepository.findActiveVersion(
                context.provider(),
                context.modelCode(),
                context.providerUsage().usageFamily().name(),
                occurredAt
        )
                : pricingVersionRepository.findActiveVersionByModelId(context.modelId(), occurredAt);
        LlmUsageChargeLedger chargeLedger = buildChargeLedger(event, context.providerUsage(), pricingVersion);
        chargeLedger = chargeLedgerRepository.save(chargeLedger);

        Optional<UsageCreditPolicyVersion> creditPolicyVersion = creditPolicyVersionRepository.findActiveVersion(
                context.purpose(),
                chargeBucket.name(),
                context.providerUsage().usageFamily().name(),
                occurredAt
        );
        LlmUsageCreditLedger creditLedger = buildCreditLedger(event, chargeBucket, context, creditPolicyVersion);
        if (creditLedger.getChargeStatus() == ChargeStatus.CHARGEABLE && creditLedger.getChargedCreditsMicros() != null) {
            try {
                var debitResult = billingUsageDebitService.debit(
                        context.userId(),
                        chargeBucket.name(),
                        creditLedger.getChargedCreditsMicros(),
                        occurredAt,
                        String.valueOf(event.getId())
                );
                creditLedger.setMetadata(serializeMetadata(Map.of(
                        "billingEventId", debitResult.billingEvent().getId(),
                        "subscriptionAllocatedMicros", debitResult.subscriptionAllocatedMicros(),
                        "purchasedAllocatedMicros", debitResult.purchasedAllocatedMicros()
                )));
            } catch (BusinessException exception) {
                if (ErrorCode.USER_BILLING_001.equals(exception.getErrorCode())) {
                    usageRejectionRecordingService.recordDebitRejected(
                            context,
                            chargeBucket.name(),
                            com.josh.interviewj.usage.model.UsageRejectionReasonCode.INSUFFICIENT_CREDITS,
                            exception.getMessage(),
                            occurredAt
                    );
                }
                throw exception;
            }
        }
        creditLedger = creditLedgerRepository.save(creditLedger);

        LocalDateTime periodStart = occurredAt.withDayOfMonth(1).toLocalDate().atStartOfDay();
        LocalDateTime periodEnd = periodStart.plusMonths(1);
        if (creditLedger.getChargeStatus() == ChargeStatus.CHARGEABLE
                && creditLedger.getChargedCreditsMicros() != null
                && creditLedger.getMetadata() != null) {
            Map<String, Object> debitMetadata = readMetadata(creditLedger.getMetadata());
            long subscriptionAllocatedMicros = longValue(debitMetadata.get("subscriptionAllocatedMicros"));
            userCreditPeriodService.incrementUsedCredits(
                    context.userId(),
                    chargeBucket,
                    periodStart,
                    periodEnd,
                    subscriptionAllocatedMicros
            );
        }

        usageInternalPeriodService.increment(
                context.provider(),
                context.modelCode(),
                context.providerUsage().usageFamily(),
                context.purpose(),
                periodStart,
                periodEnd,
                longValue(context.providerUsage().totalTokens()),
                longValue(context.providerUsage().cachedTokens()),
                longValue(context.providerUsage().requestCount()),
                chargeLedger.getChargeStatus() == ChargeStatus.CHARGEABLE ? totalChargeableTokens(context.providerUsage()) : 0L,
                chargeLedger.getChargeStatus() == ChargeStatus.CHARGEABLE ? requestUnits(context.providerUsage(), activeBillingUnit(pricingVersion)) : 0L,
                chargeLedger.getChargeStatus() == ChargeStatus.CHARGEABLE ? chargeLedger.getTotalAmount() : zeroAmount(),
                chargeLedger.getCurrency() == null ? DEFAULT_CURRENCY : chargeLedger.getCurrency()
        );

        return new UsageSettlementResult(false, event, chargeLedger, creditLedger);
    }

    private LlmUsageEvent buildEvent(UsageOperationContext context, ChargeBucket chargeBucket, LocalDateTime occurredAt) {
        ProviderUsage usage = context.providerUsage();
        return LlmUsageEvent.builder()
                .userId(context.userId())
                .usageFamily(usage.usageFamily())
                .purpose(context.purpose())
                .provider(context.provider())
                .providerId(context.providerId())
                .modelCode(context.modelCode())
                .modelId(context.modelId())
                .resourceType(context.resourceType())
                .resourceExternalId(context.resourceExternalId())
                .operationId(context.operationId())
                .businessOperationId(context.businessOperationId())
                .requestCount(longValue(usage.requestCount()))
                .promptTokens(usage.promptTokens())
                .completionTokens(usage.completionTokens())
                .totalTokens(usage.totalTokens())
                .cachedTokens(usage.cachedTokens())
                .chargeBucket(chargeBucket)
                .businessOutcome(context.businessOutcome().name())
                .executionDisposition(context.executionDisposition())
                .failureReason(context.failureReason())
                .metadata(serializeMetadata(context.metadata()))
                .dedupeKey(buildDedupeKey(context))
                .occurredAt(occurredAt)
                .build();
    }

    private LlmUsageChargeLedger buildChargeLedger(
            LlmUsageEvent event,
            ProviderUsage usage,
            Optional<LlmModelPricingVersion> pricingVersion
    ) {
        if (pricingVersion.isEmpty()) {
            return LlmUsageChargeLedger.builder()
                    .usageEventId(event.getId())
                    .promptTokenUnits(0L)
                    .completionTokenUnits(0L)
                    .cachedTokenUnits(0L)
                    .requestUnits(0L)
                    .promptAmount(zeroAmount())
                    .completionAmount(zeroAmount())
                    .cachedAmount(zeroAmount())
                    .requestAmount(zeroAmount())
                    .totalAmount(zeroAmount())
                    .currency(DEFAULT_CURRENCY)
                    .chargeStatus(ChargeStatus.PENDING)
                    .metadata(serializeMetadata(Map.of("reason", PRICING_VERSION_MISSING)))
                    .build();
        }

        LlmModelPricingVersion version = pricingVersion.get();
        long promptUnits = promptUnits(usage, version.getBillingUnit());
        long cachedUnits = cachedUnits(usage, version.getBillingUnit());
        long completionUnits = completionUnits(usage, version.getBillingUnit());
        long requestUnits = requestUnits(usage, version.getBillingUnit());

        BigDecimal promptAmount = multiply(promptUnits, version.getPromptTokenPrice());
        BigDecimal cachedAmount = multiply(cachedUnits, version.getCachedTokenPrice());
        BigDecimal completionAmount = multiply(completionUnits, version.getCompletionTokenPrice());
        BigDecimal requestAmount = multiply(requestUnits, version.getRequestPrice());
        BigDecimal totalAmount = promptAmount.add(cachedAmount).add(completionAmount).add(requestAmount).setScale(6, RoundingMode.HALF_UP);

        return LlmUsageChargeLedger.builder()
                .usageEventId(event.getId())
                .pricingVersionId(version.getId())
                .promptTokenUnits(promptUnits)
                .completionTokenUnits(completionUnits)
                .cachedTokenUnits(cachedUnits)
                .requestUnits(requestUnits)
                .promptAmount(promptAmount)
                .completionAmount(completionAmount)
                .cachedAmount(cachedAmount)
                .requestAmount(requestAmount)
                .totalAmount(totalAmount)
                .currency(version.getCurrency())
                .chargeStatus(ChargeStatus.CHARGEABLE)
                .build();
    }

    private LlmUsageCreditLedger buildCreditLedger(
            LlmUsageEvent event,
            ChargeBucket chargeBucket,
            UsageOperationContext context,
            Optional<UsageCreditPolicyVersion> creditPolicyVersion
    ) {
        if (context.businessOutcome() == UsageBusinessOutcome.FAILED_NON_CHARGEABLE
                || context.businessOutcome() == UsageBusinessOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE) {
            return LlmUsageCreditLedger.builder()
                    .usageEventId(event.getId())
                    .chargeBucket(chargeBucket)
                    .chargeStatus(ChargeStatus.NON_CHARGEABLE)
                    .build();
        }

        if (context.businessOutcome() != UsageBusinessOutcome.SUCCESS) {
            return LlmUsageCreditLedger.builder()
                    .usageEventId(event.getId())
                    .chargeBucket(chargeBucket)
                    .chargeStatus(ChargeStatus.PENDING)
                    .build();
        }

        if (creditPolicyVersion.isEmpty()) {
            return LlmUsageCreditLedger.builder()
                    .usageEventId(event.getId())
                    .chargeBucket(chargeBucket)
                    .chargeStatus(ChargeStatus.PENDING)
                    .build();
        }

        UsageCreditPolicyVersion version = creditPolicyVersion.get();
        long chargedCreditsMicros = calculateCreditsMicros(context.providerUsage(), version);
        return LlmUsageCreditLedger.builder()
                .usageEventId(event.getId())
                .creditPolicyVersionId(version.getId())
                .chargeBucket(chargeBucket)
                .chargedCreditsMicros(chargedCreditsMicros)
                .chargeStatus(ChargeStatus.CHARGEABLE)
                .build();
    }

    private long calculateCreditsMicros(ProviderUsage usage, UsageCreditPolicyVersion version) {
        long total = 0L;
        if (version.getBillingUnit() == BillingUnit.TOKEN || version.getBillingUnit() == BillingUnit.TOKEN_AND_REQUEST) {
            total += scaledLong(promptUnits(usage, version.getBillingUnit()), version.getPromptTokenRatio());
            total += scaledLong(completionUnits(usage, version.getBillingUnit()), version.getCompletionTokenRatio());
            total += scaledLong(cachedUnits(usage, version.getBillingUnit()), version.getCachedTokenRatio());
        }
        if (version.getBillingUnit() == BillingUnit.REQUEST || version.getBillingUnit() == BillingUnit.TOKEN_AND_REQUEST) {
            total += scaledLong(requestUnits(usage, version.getBillingUnit()), version.getRequestRatio());
        }
        return total;
    }

    private String buildDedupeKey(UsageOperationContext context) {
        return String.join(
                "|",
                context.purpose(),
                context.resourceType(),
                context.resourceExternalId(),
                context.operationId(),
                context.providerUsage().usageFamily().name()
        );
    }

    private Map<String, Object> readMetadata(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawValue, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private long promptUnits(ProviderUsage usage, BillingUnit billingUnit) {
        if (billingUnit != BillingUnit.TOKEN && billingUnit != BillingUnit.TOKEN_AND_REQUEST) {
            return 0L;
        }
        long promptTokens = longValue(usage.promptTokens());
        long cachedTokens = longValue(usage.cachedTokens());
        return Math.max(promptTokens - cachedTokens, 0L);
    }

    private long cachedUnits(ProviderUsage usage, BillingUnit billingUnit) {
        if (billingUnit != BillingUnit.TOKEN && billingUnit != BillingUnit.TOKEN_AND_REQUEST) {
            return 0L;
        }
        return longValue(usage.cachedTokens());
    }

    private long completionUnits(ProviderUsage usage, BillingUnit billingUnit) {
        if (billingUnit != BillingUnit.TOKEN && billingUnit != BillingUnit.TOKEN_AND_REQUEST) {
            return 0L;
        }
        return longValue(usage.completionTokens());
    }

    private long requestUnits(ProviderUsage usage, BillingUnit billingUnit) {
        if (billingUnit != BillingUnit.REQUEST && billingUnit != BillingUnit.TOKEN_AND_REQUEST) {
            return 0L;
        }
        return longValue(usage.requestCount());
    }

    private long totalChargeableTokens(ProviderUsage usage) {
        return promptUnits(usage, BillingUnit.TOKEN_AND_REQUEST)
                + cachedUnits(usage, BillingUnit.TOKEN_AND_REQUEST)
                + completionUnits(usage, BillingUnit.TOKEN_AND_REQUEST);
    }

    private BillingUnit activeBillingUnit(Optional<LlmModelPricingVersion> pricingVersion) {
        return pricingVersion.map(LlmModelPricingVersion::getBillingUnit).orElse(BillingUnit.TOKEN_AND_REQUEST);
    }

    private BigDecimal multiply(long units, BigDecimal price) {
        if (units == 0L || price == null) {
            return zeroAmount();
        }
        return price.multiply(BigDecimal.valueOf(units)).setScale(6, RoundingMode.HALF_UP);
    }

    private long scaledLong(long units, BigDecimal ratio) {
        if (units == 0L || ratio == null) {
            return 0L;
        }
        return ratio.multiply(BigDecimal.valueOf(units))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    private long longValue(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal zeroAmount() {
        return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize usage settlement metadata", exception);
        }
    }
}
