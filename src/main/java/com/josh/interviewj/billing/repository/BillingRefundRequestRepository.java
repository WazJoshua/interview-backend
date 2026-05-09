package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.BillingRefundRequest;
import com.josh.interviewj.billing.model.BillingRefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BillingRefundRequestRepository extends JpaRepository<BillingRefundRequest, Long> {

    Optional<BillingRefundRequest> findFirstByPaymentOrderIdAndStatusInOrderByCreatedAtDescIdDesc(
            Long paymentOrderId,
            Collection<BillingRefundStatus> statuses
    );

    List<BillingRefundRequest> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    List<BillingRefundRequest> findByStatusOrderByCreatedAtDescIdDesc(BillingRefundStatus status);
}
