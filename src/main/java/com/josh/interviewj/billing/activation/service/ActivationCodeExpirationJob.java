package com.josh.interviewj.billing.activation.service;

import com.josh.interviewj.billing.activation.repository.ActivationCodeRepository;
import com.josh.interviewj.billing.config.BillingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        value = "app.billing.activation-code.expiration-check-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ActivationCodeExpirationJob {

    private final Clock clock;
    private final BillingProperties billingProperties;
    private final ActivationCodeRepository activationCodeRepository;

    @Scheduled(fixedDelayString = "#{@billingProperties.activationCode.expirationCheckPollInterval.toMillis()}")
    @Transactional
    public void processExpiredCodes() {
        if (!billingProperties.getActivationCode().isExpirationCheckEnabled()) {
            return;
        }
        int expiredCount = activationCodeRepository.expireOverdueCodes(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        if (expiredCount > 0) {
            log.info("Activation code expiration job completed: expired {} codes", expiredCount);
        }
    }
}
