package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.CreditLot;
import com.josh.interviewj.billing.model.CreditLotStatus;
import com.josh.interviewj.billing.model.CreditWallet;
import com.josh.interviewj.billing.repository.CreditLotRepository;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreditBalanceProjectionService {

    private final BillingSnapshotCodec billingSnapshotCodec;
    private final CreditLotRepository creditLotRepository;
    private final CreditWalletRepository creditWalletRepository;

    @Transactional
    public CreditLot grantPurchasedCredits(
            Long userId,
            BillingEvent sourceEvent,
            long amountMicros,
            LocalDateTime expiresAt,
            Map<String, Object> metadata
    ) {
        CreditLot existing = creditLotRepository.findBySourceBillingEventId(sourceEvent.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        CreditLot lot = creditLotRepository.save(CreditLot.builder()
                .externalId(UUID.randomUUID())
                .userId(userId)
                .sourceBillingEventId(sourceEvent.getId())
                .originalAmountMicros(amountMicros)
                .remainingAmountMicros(amountMicros)
                .expiresAt(expiresAt)
                .status(CreditLotStatus.ACTIVE)
                .metadata(billingSnapshotCodec.write(metadata == null ? Map.of() : metadata))
                .build());
        adjustWallet(userId, amountMicros);
        return lot;
    }

    @Transactional
    public CreditWallet adjustWallet(Long userId, long deltaAmountMicros) {
        int updatedRows = creditWalletRepository.incrementPurchasedBalance(userId, deltaAmountMicros);
        if (updatedRows == 1) {
            return creditWalletRepository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException("Credit wallet disappeared after update"));
        }

        try {
            return creditWalletRepository.save(CreditWallet.builder()
                    .userId(userId)
                    .purchasedBalanceMicros(deltaAmountMicros)
                    .build());
        } catch (DataIntegrityViolationException exception) {
            int retriedRows = creditWalletRepository.incrementPurchasedBalance(userId, deltaAmountMicros);
            if (retriedRows != 1) {
                throw exception;
            }
            return creditWalletRepository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException("Credit wallet disappeared after retry update"));
        }
    }
}
