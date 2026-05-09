package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.BillingPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingPlanRepository extends JpaRepository<BillingPlan, Long> {

    Optional<BillingPlan> findByExternalId(UUID externalId);

    Optional<BillingPlan> findByPlanCode(String planCode);
}
