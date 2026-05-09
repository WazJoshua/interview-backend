package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.PaymentEvent;
import com.josh.interviewj.billing.model.PaymentEventProcessStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    java.util.Optional<PaymentEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    List<PaymentEvent> findByProcessStatusInOrderByLastAttemptAtAscIdAsc(
            Collection<PaymentEventProcessStatus> processStatuses,
            Pageable pageable
    );

    Optional<PaymentEvent> findTopByPaymentOrderIdAndProcessStatusOrderByOccurredAtDescIdDesc(
            Long paymentOrderId,
            PaymentEventProcessStatus processStatus
    );

    @Transactional
    @Modifying
    @Query("""
            UPDATE PaymentEvent event
            SET event.processStatus = :applyingStatus,
                event.applyAttemptCount = event.applyAttemptCount + 1,
                event.lastAttemptAt = :attemptedAt,
                event.updatedAt = :attemptedAt
            WHERE event.id = :paymentEventId
              AND event.processStatus IN :eligibleStatuses
            """)
    int claimForApplying(
            @Param("paymentEventId") Long paymentEventId,
            @Param("eligibleStatuses") Collection<PaymentEventProcessStatus> eligibleStatuses,
            @Param("applyingStatus") PaymentEventProcessStatus applyingStatus,
            @Param("attemptedAt") LocalDateTime attemptedAt
    );
}
