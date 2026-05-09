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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActivationCodeService {

    private static final int MAX_GENERATE_ATTEMPTS = 10;

    private final ActivationCodeRepository activationCodeRepository;
    private final ActivationCodeFormatService formatService;
    private final SubscriptionLifecycleService subscriptionLifecycleService;
    private final CreditBalanceProjectionService creditBalanceProjectionService;
    private final BillingEventService billingEventService;
    private final SubscriptionContractRepository subscriptionContractRepository;
    private final BillingPlanVersionRepository billingPlanVersionRepository;
    private final BillingPlanRepository billingPlanRepository;
    private final RuntimeSwitchService runtimeSwitchService;
    private final Clock clock;

    @Transactional
    public ActivationCode generateCode(Long createdByUserId, CreateActivationCodeRequest request) {
        ensureActivationCodeCapabilityEnabled();
        validateGeneratePayload(
                request.getCodeType(),
                request.getBillingPlanVersionId(),
                request.getSubscriptionDurationDays(),
                request.getCreditAmountMicros()
        );
        return saveWithUniqueRetry(request.getCodeType(), code -> ActivationCode.builder()
                .code(code)
                .codeType(request.getCodeType())
                .billingPlanVersionId(request.getBillingPlanVersionId())
                .subscriptionDurationDays(request.getSubscriptionDurationDays())
                .creditAmountMicros(request.getCreditAmountMicros())
                .expiresAt(toLocal(request.getExpiresAt()))
                .createdByUserId(createdByUserId)
                .note(request.getNote())
                .build());
    }

    @Transactional
    public List<ActivationCode> generateBatch(Long createdByUserId, CreateActivationCodeBatchRequest request) {
        ensureActivationCodeCapabilityEnabled();
        validateGeneratePayload(
                request.getCodeType(),
                request.getBillingPlanVersionId(),
                request.getSubscriptionDurationDays(),
                request.getCreditAmountMicros()
        );
        UUID batchId = UUID.randomUUID();
        List<ActivationCode> results = new ArrayList<>();
        for (int i = 0; i < request.getCount(); i++) {
            results.add(saveWithUniqueRetry(request.getCodeType(), code -> ActivationCode.builder()
                    .code(code)
                    .codeType(request.getCodeType())
                    .billingPlanVersionId(request.getBillingPlanVersionId())
                    .subscriptionDurationDays(request.getSubscriptionDurationDays())
                    .creditAmountMicros(request.getCreditAmountMicros())
                    .expiresAt(toLocal(request.getExpiresAt()))
                    .batchId(batchId)
                    .createdByUserId(createdByUserId)
                    .note(request.getNote())
                    .build()));
        }
        return results;
    }

    @Transactional
    public RedeemResultResponse redeem(Long userId, String rawCode) {
        ensureActivationCodeCapabilityEnabled();
        String normalizedCode = formatService.normalize(rawCode);
        ActivationCode code = activationCodeRepository.findByCodeForUpdate(normalizedCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.BILLING_ACTIVATION_001, "Activation code not found"));
        validateRedeemable(code);

        LocalDateTime now = nowUtc();
        RedeemResultResponse response = switch (code.getCodeType()) {
            case SUBSCRIPTION -> redeemSubscription(userId, code, now);
            case CREDIT -> redeemCredit(userId, code, now);
        };

        code.setStatus(ActivationCodeStatus.REDEEMED);
        code.setRedeemedByUserId(userId);
        code.setRedeemedAt(now);
        activationCodeRepository.save(code);
        return response;
    }

    @Transactional
    public void voidCode(Long codeId) {
        ensureActivationCodeCapabilityEnabled();
        ActivationCode code = activationCodeRepository.findById(codeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BILLING_ACTIVATION_001, "Activation code not found"));
        if (code.getStatus() != ActivationCodeStatus.UNUSED) {
            throw new BusinessException(ErrorCode.BILLING_ACTIVATION_003, "Activation code is not available");
        }
        code.setStatus(ActivationCodeStatus.VOIDED);
        activationCodeRepository.save(code);
    }

    private RedeemResultResponse redeemSubscription(Long userId, ActivationCode code, LocalDateTime now) {
        BillingPlanVersion newVersion = billingPlanVersionRepository.findById(code.getBillingPlanVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_BILLING_003, "Plan version not found"));
        BillingPlan newPlan = billingPlanRepository.findById(newVersion.getBillingPlanId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_BILLING_003, "Plan not found"));

        Optional<SubscriptionContract> existingContract = subscriptionContractRepository.findOpenContractByUserId(userId);
        if (existingContract.isPresent()) {
            SubscriptionContract currentContract = existingContract.get();
            BillingPlan currentPlan = billingPlanRepository.findById(currentContract.getBillingPlanId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_BILLING_003, "Current plan not found"));
            if (newPlan.getTierRank() <= currentPlan.getTierRank()) {
                throw new BusinessException(ErrorCode.BILLING_ACTIVATION_002,
                        "Cannot redeem: current subscription is same or higher tier");
            }
            currentContract.setStatus(SubscriptionContractStatus.CANCELED);
            subscriptionContractRepository.save(currentContract);
        }

        SubscriptionContract contract = subscriptionLifecycleService.applyActivationCodeSubscription(
                userId,
                code.getBillingPlanVersionId(),
                code.getSubscriptionDurationDays(),
                String.valueOf(code.getId())
        );
        BillingEvent billingEvent = billingEventService.createOrGet(
                userId,
                BillingEventType.ACTIVATION_CODE_SUBSCRIPTION_GRANTED,
                "ACTIVATION_CODE",
                String.valueOf(code.getId()),
                "ACTIVATION_CODE|" + code.getId() + "|subscription-granted",
                0L,
                null,
                now,
                Map.of()
        );
        code.setBillingEventId(billingEvent.getId());

        return RedeemResultResponse.builder()
                .codeType(ActivationCodeType.SUBSCRIPTION)
                .grantedPlanName(newPlan.getDisplayName())
                .grantedDurationDays(code.getSubscriptionDurationDays())
                .subscriptionExpiresAt(toOffset(contract.getCurrentPeriodEnd()))
                .build();
    }

    private RedeemResultResponse redeemCredit(Long userId, ActivationCode code, LocalDateTime now) {
        BillingEvent billingEvent = billingEventService.createOrGet(
                userId,
                BillingEventType.ACTIVATION_CODE_CREDIT_GRANTED,
                "ACTIVATION_CODE",
                String.valueOf(code.getId()),
                "ACTIVATION_CODE|" + code.getId() + "|credit-granted",
                code.getCreditAmountMicros(),
                null,
                now,
                Map.of(
                        "activationCodeId", code.getId(),
                        "creditAmountMicros", code.getCreditAmountMicros()
                )
        );
        creditBalanceProjectionService.grantPurchasedCredits(
                userId,
                billingEvent,
                code.getCreditAmountMicros(),
                null,
                Map.of(
                        "source", "ACTIVATION_CODE",
                        "activationCodeId", code.getId()
                )
        );
        code.setBillingEventId(billingEvent.getId());

        return RedeemResultResponse.builder()
                .codeType(ActivationCodeType.CREDIT)
                .grantedCredits(code.getCreditAmountMicros())
                .build();
    }

    private void validateRedeemable(ActivationCode code) {
        if (code.getStatus() != ActivationCodeStatus.UNUSED) {
            throw new BusinessException(ErrorCode.BILLING_ACTIVATION_003, "Activation code is not available");
        }
        if (code.getExpiresAt() != null && !code.getExpiresAt().isAfter(nowUtc())) {
            throw new BusinessException(ErrorCode.BILLING_ACTIVATION_003, "Activation code has expired");
        }
    }

    private void validateGeneratePayload(
            ActivationCodeType codeType,
            Long billingPlanVersionId,
            Integer subscriptionDurationDays,
            Long creditAmountMicros
    ) {
        if (codeType == null) {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_001, "Activation code type is required");
        }

        if (codeType == ActivationCodeType.SUBSCRIPTION) {
            if (billingPlanVersionId == null || subscriptionDurationDays == null || subscriptionDurationDays <= 0) {
                throw new BusinessException(ErrorCode.ADMIN_BILLING_001,
                        "SUBSCRIPTION type requires billingPlanVersionId and subscriptionDurationDays > 0");
            }
            if (creditAmountMicros != null) {
                throw new BusinessException(ErrorCode.ADMIN_BILLING_001,
                        "SUBSCRIPTION type does not allow creditAmountMicros");
            }
            billingPlanVersionRepository.findById(billingPlanVersionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_BILLING_003, "Plan version not found"));
            return;
        }

        if (creditAmountMicros == null || creditAmountMicros <= 0) {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_001,
                    "CREDIT type requires creditAmountMicros > 0");
        }
        if (billingPlanVersionId != null || subscriptionDurationDays != null) {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_001,
                    "CREDIT type does not allow billingPlanVersionId or subscriptionDurationDays");
        }
    }

    private ActivationCode saveWithUniqueRetry(
            ActivationCodeType codeType,
            java.util.function.Function<String, ActivationCode> entityFactory
    ) {
        for (int i = 0; i < MAX_GENERATE_ATTEMPTS; i++) {
            String candidate = formatService.generate(codeType);
            if (activationCodeRepository.findByCode(candidate).isEmpty()) {
                try {
                    return activationCodeRepository.save(entityFactory.apply(candidate));
                } catch (DataIntegrityViolationException exception) {
                    if (!isUniqueCodeViolation(exception)) {
                        throw exception;
                    }
                }
            }
        }
        throw new BusinessException(ErrorCode.ADMIN_BILLING_002,
                "Failed to generate unique activation code after retry");
    }

    private boolean isUniqueCodeViolation(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("uk_activation_code_code")
                        || normalized.contains("activation_code_code_key")
                        || normalized.contains("duplicate key")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void ensureActivationCodeCapabilityEnabled() {
        runtimeSwitchService.requireActivationCodeEnabled();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
