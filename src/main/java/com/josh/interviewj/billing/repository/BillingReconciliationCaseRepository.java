package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.BillingReconciliationCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillingReconciliationCaseRepository extends JpaRepository<BillingReconciliationCase, Long> {

    List<BillingReconciliationCase> findByStatusOrderByCreatedAtDescIdDesc(String status);
}
