package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.LlmUsageChargeLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LlmUsageChargeLedgerRepository extends JpaRepository<LlmUsageChargeLedger, Long> {

    Optional<LlmUsageChargeLedger> findByUsageEventId(Long usageEventId);
}
