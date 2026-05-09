package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionContractRepository extends JpaRepository<SubscriptionContract, Long> {

    Optional<SubscriptionContract> findByExternalId(UUID externalId);

    Optional<SubscriptionContract> findByProviderSubscriptionRef(String providerSubscriptionRef);

    @Query(
            value = """
                    SELECT *
                    FROM subscription_contract contract
                    WHERE contract.user_id = :userId
                      AND contract.status IN ('PENDING_ACTIVATION', 'ACTIVE', 'PAST_DUE')
                    ORDER BY contract.created_at DESC, contract.id DESC
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<SubscriptionContract> findOpenContractByUserId(@Param("userId") Long userId);

    @Query(
            value = """
                    SELECT *
                    FROM subscription_contract contract
                    WHERE contract.status = 'ACTIVE'
                      AND contract.current_period_end IS NOT NULL
                      AND contract.current_period_end < :cutoff
                      AND (contract.grace_until IS NULL OR contract.grace_until < :cutoff)
                    ORDER BY contract.current_period_end ASC, contract.id ASC
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<SubscriptionContract> findExpiredContracts(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit);

    List<SubscriptionContract> findByStatusIn(Collection<SubscriptionContractStatus> statuses);
}
