package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByExternalId(UUID externalId);

    Optional<PaymentOrder> findByOrderNo(String orderNo);

    Optional<PaymentOrder> findByOrderNoAndUserId(String orderNo, Long userId);

    Optional<PaymentOrder> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<PaymentOrder> findBySubscriptionContractIdAndRenewalPeriodStartAndRenewalPeriodEndAndOrderType(
            Long subscriptionContractId,
            LocalDateTime renewalPeriodStart,
            LocalDateTime renewalPeriodEnd,
            PaymentOrderType orderType
    );

    Optional<PaymentOrder> findByProviderAndProviderOrderRef(String provider, String providerOrderRef);

    @Query(value = """
            SELECT *
            FROM payment_order
            WHERE user_id = :userId
              AND status IN ('CREATED', 'PENDING_PROVIDER', 'AWAITING_CONFIRMATION')
              AND payable_activated_at IS NOT NULL
            ORDER BY created_at DESC, id DESC
            """, nativeQuery = true)
    List<PaymentOrder> findActivePayableOrdersByUserId(@Param("userId") Long userId);

    @Query(value = """
            SELECT *
            FROM payment_order
            WHERE user_id = :userId
              AND status IN ('CREATED', 'PENDING_PROVIDER', 'AWAITING_CONFIRMATION')
              AND payable_activated_at IS NOT NULL
            ORDER BY created_at DESC, id DESC
            FOR UPDATE
            """, nativeQuery = true)
    List<PaymentOrder> findActivePayableOrdersByUserIdForUpdate(@Param("userId") Long userId);

    @Query(value = """
            SELECT *
            FROM payment_order
            WHERE status IN ('CREATED', 'PENDING_PROVIDER', 'AWAITING_CONFIRMATION')
              AND payable_activated_at IS NOT NULL
              AND expires_at < :cutoff
            ORDER BY expires_at ASC, id ASC
            """, nativeQuery = true)
    List<PaymentOrder> findExpirableOrders(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    default List<PaymentOrder> findExpirableOrders(LocalDateTime cutoff, int limit) {
        return findExpirableOrders(cutoff, Pageable.ofSize(limit));
    }

    @Query(value = """
            SELECT *
            FROM payment_order
            WHERE status IN ('PENDING_PROVIDER', 'AWAITING_CONFIRMATION')
              AND updated_at < :updatedBefore
            ORDER BY updated_at ASC, id ASC
            """, nativeQuery = true)
    List<PaymentOrder> findOrdersForPaymentStatusSync(
            @Param("updatedBefore") LocalDateTime updatedBefore,
            Pageable pageable
    );

    default List<PaymentOrder> findOrdersForPaymentStatusSync(LocalDateTime updatedBefore, int limit) {
        return findOrdersForPaymentStatusSync(updatedBefore, Pageable.ofSize(limit));
    }

    /**
     * Find orders with a specific status that expired before the cutoff time.
     * Used for batch expiration processing.
     */
    List<PaymentOrder> findByStatusAndExpiresAtBefore(
            PaymentOrderStatus status,
            LocalDateTime expiresAtBefore,
            Pageable pageable
    );

    /**
     * Convenience overload for batch size limit.
     */
    default List<PaymentOrder> findByStatusAndExpiresAtBefore(
            PaymentOrderStatus status,
            LocalDateTime expiresAtBefore,
            int limit
    ) {
        return findByStatusAndExpiresAtBefore(status, expiresAtBefore, Pageable.ofSize(limit));
    }
}
