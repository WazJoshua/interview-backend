package com.josh.interviewj.common.job;

import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionExpirationJobTest {

    @Mock
    private SubscriptionContractRepository subscriptionContractRepository;

    private BillingProperties billingProperties;
    private Clock clock;
    private SubscriptionExpirationJob sut;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-03T10:00:00Z"), ZoneOffset.UTC);
        billingProperties = new BillingProperties();
        sut = new SubscriptionExpirationJob(clock, billingProperties, subscriptionContractRepository);
    }

    @Test
    void processExpiredContractsExpiresContractsReturnedByQuery() {
        SubscriptionContract expired = SubscriptionContract.builder()
                .id(1L)
                .status(SubscriptionContractStatus.ACTIVE)
                .currentPeriodEnd(LocalDateTime.of(2026, 4, 2, 0, 0))
                .build();
        LocalDateTime expectedCutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        when(subscriptionContractRepository.findExpiredContracts(expectedCutoff, 100))
                .thenReturn(List.of(expired));
        when(subscriptionContractRepository.save(any(SubscriptionContract.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        sut.processExpiredContracts();

        assertThat(expired.getStatus()).isEqualTo(SubscriptionContractStatus.EXPIRED);
        verify(subscriptionContractRepository).save(expired);
    }

    @Test
    void processExpiredContractsDisabledDoesNothing() {
        billingProperties.getSubscriptionExpiration().setEnabled(false);

        sut.processExpiredContracts();

        verifyNoInteractions(subscriptionContractRepository);
    }
}
