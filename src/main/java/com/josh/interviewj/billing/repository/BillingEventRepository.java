package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BillingEventRepository extends JpaRepository<BillingEvent, Long> {

    Optional<BillingEvent> findByIdempotencyKey(String idempotencyKey);

    List<BillingEvent> findByUserIdAndEventTypeOrderByOccurredAtDescIdDesc(Long userId, BillingEventType eventType);

    List<BillingEvent> findByUserIdOrderByOccurredAtDescIdDesc(Long userId);

    Page<BillingEvent> findByUserId(Long userId, Pageable pageable);

    @Query(
            value = """
                    SELECT COALESCE(SUM(b.delta_amount_micros), 0)
                    FROM billing_event b
                    WHERE b.user_id = :userId
                      AND (
                        b.event_type IN ('CREDIT_PURCHASE_GRANTED', 'ACTIVATION_CODE_CREDIT_GRANTED')
                        OR b.event_type IN ('PAYMENT_REFUNDED', 'PAYMENT_CHARGEBACK')
                        OR (
                            b.event_type = 'MANUAL_ADJUSTMENT'
                            AND b.bucket_code IS NULL
                            AND b.delta_amount_micros <> 0
                        )
                      )
                    """,
            nativeQuery = true
    )
    Long sumNetPurchasedCreditsMicros(@Param("userId") Long userId);

    Optional<BillingEvent> findFirstByUserIdAndEventTypeAndSourceTypeAndSourceIdOrderByOccurredAtDescIdDesc(
            Long userId,
            BillingEventType eventType,
            String sourceType,
            String sourceId
    );
}
