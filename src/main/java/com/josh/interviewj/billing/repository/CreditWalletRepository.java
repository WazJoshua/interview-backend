package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.CreditWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CreditWalletRepository extends JpaRepository<CreditWallet, Long> {

    Optional<CreditWallet> findByUserId(Long userId);

    @Modifying
    @Query("""
            UPDATE CreditWallet wallet
            SET wallet.purchasedBalanceMicros = wallet.purchasedBalanceMicros + :deltaAmountMicros
            WHERE wallet.userId = :userId
            """)
    int incrementPurchasedBalance(
            @Param("userId") Long userId,
            @Param("deltaAmountMicros") long deltaAmountMicros
    );
}
