package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.CreditWallet;
import com.josh.interviewj.billing.repository.CreditLotRepository;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditBalanceProjectionServiceTest {

    @Mock
    private CreditLotRepository creditLotRepository;

    @Mock
    private CreditWalletRepository creditWalletRepository;

    private CreditBalanceProjectionService service;

    @BeforeEach
    void setUp() {
        service = new CreditBalanceProjectionService(
                new BillingSnapshotCodec(JsonMapper.builder().build()),
                creditLotRepository,
                creditWalletRepository
        );
    }

    @Test
    void adjustWallet_WhenWalletExists_UsesAtomicIncrementPath() {
        when(creditWalletRepository.incrementPurchasedBalance(101L, 50L)).thenReturn(1);
        when(creditWalletRepository.findByUserId(101L)).thenReturn(Optional.of(CreditWallet.builder()
                .id(1L)
                .userId(101L)
                .purchasedBalanceMicros(150L)
                .build()));

        CreditWallet wallet = service.adjustWallet(101L, 50L);

        assertThat(wallet.getPurchasedBalanceMicros()).isEqualTo(150L);
        verify(creditWalletRepository, never()).save(any(CreditWallet.class));
    }

    @Test
    void adjustWallet_WhenConcurrentCreateHappens_RetriesAtomicIncrement() {
        when(creditWalletRepository.incrementPurchasedBalance(101L, 50L)).thenReturn(0, 1);
        when(creditWalletRepository.save(any(CreditWallet.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate wallet"));
        when(creditWalletRepository.findByUserId(101L)).thenReturn(Optional.of(CreditWallet.builder()
                .id(2L)
                .userId(101L)
                .purchasedBalanceMicros(50L)
                .build()));

        CreditWallet wallet = service.adjustWallet(101L, 50L);

        assertThat(wallet.getPurchasedBalanceMicros()).isEqualTo(50L);
        verify(creditWalletRepository, times(2)).incrementPurchasedBalance(101L, 50L);
        verify(creditWalletRepository).save(any(CreditWallet.class));
    }
}
