package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.SubscriptionQuotaGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionQuotaGrantRepository extends JpaRepository<SubscriptionQuotaGrant, Long> {

    List<SubscriptionQuotaGrant> findBySubscriptionContractIdOrderByPeriodEndAscIdAsc(Long subscriptionContractId);

    Optional<SubscriptionQuotaGrant> findBySubscriptionContractIdAndPeriodStartAndPeriodEndAndBucketCode(
            Long subscriptionContractId,
            LocalDateTime periodStart,
            LocalDateTime periodEnd,
            String bucketCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT grant
            FROM SubscriptionQuotaGrant grant
            WHERE grant.subscriptionContractId = :subscriptionContractId
              AND grant.bucketCode = :bucketCode
              AND grant.periodStart <= :occurredAt
              AND grant.periodEnd > :occurredAt
              AND grant.usedAmountMicros < grant.grantedAmountMicros
            ORDER BY grant.periodEnd ASC, grant.id ASC
            """)
    List<SubscriptionQuotaGrant> findConsumableGrantsForUpdate(
            @Param("subscriptionContractId") Long subscriptionContractId,
            @Param("bucketCode") String bucketCode,
            @Param("occurredAt") LocalDateTime occurredAt
    );
}
