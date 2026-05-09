package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.UsageRejectionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UsageRejectionRecordRepository extends JpaRepository<UsageRejectionRecord, Long> {

    Optional<UsageRejectionRecord> findByExternalId(UUID externalId);

    Optional<UsageRejectionRecord> findByDedupeKey(String dedupeKey);
}
