package com.josh.interviewj.billing.activation.service;

import com.josh.interviewj.billing.activation.repository.ActivationCodeRepository;
import com.josh.interviewj.billing.config.BillingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationCodeExpirationJobTest {

    @Mock
    private ActivationCodeRepository activationCodeRepository;

    private BillingProperties billingProperties;
    private Clock clock;
    private ActivationCodeExpirationJob sut;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-03T10:00:00Z"), ZoneOffset.UTC);
        billingProperties = new BillingProperties();
        sut = new ActivationCodeExpirationJob(clock, billingProperties, activationCodeRepository);
    }

    @Test
    void processExpiredCodesCallsRepositoryWithCurrentTime() {
        LocalDateTime expectedNow = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        when(activationCodeRepository.expireOverdueCodes(expectedNow)).thenReturn(5);

        sut.processExpiredCodes();

        verify(activationCodeRepository).expireOverdueCodes(expectedNow);
    }

    @Test
    void processExpiredCodesDisabledDoesNothing() {
        billingProperties.getActivationCode().setExpirationCheckEnabled(false);

        sut.processExpiredCodes();

        verifyNoInteractions(activationCodeRepository);
    }
}
