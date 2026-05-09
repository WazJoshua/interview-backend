package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.BillingPlanEntitlementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingPlanEntitlementItemRepository extends JpaRepository<BillingPlanEntitlementItem, Long> {

    List<BillingPlanEntitlementItem> findByBillingPlanVersionIdOrderByBucketCodeAsc(Long billingPlanVersionId);
}
