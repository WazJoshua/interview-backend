package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.CreditLot;
import com.josh.interviewj.billing.model.CreditLotStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreditLotRepository extends JpaRepository<CreditLot, Long> {

    Optional<CreditLot> findBySourceBillingEventId(Long sourceBillingEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT lot
            FROM CreditLot lot
            WHERE lot.userId = :userId
              AND lot.status = :status
              AND lot.remainingAmountMicros > 0
              AND (lot.expiresAt IS NULL OR lot.expiresAt > :occurredAt)
            ORDER BY lot.createdAt ASC, lot.id ASC
            """)
    List<CreditLot> findConsumableLotsForUpdate(
            @Param("userId") Long userId,
            @Param("status") CreditLotStatus status,
            @Param("occurredAt") LocalDateTime occurredAt
    );
}
