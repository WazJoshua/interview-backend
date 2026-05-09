package com.josh.interviewj.interview.repository;

import com.josh.interviewj.interview.model.InterviewReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InterviewReportRepository extends JpaRepository<InterviewReport, Long> {

    Optional<InterviewReport> findByExternalId(UUID externalId);

    Optional<InterviewReport> findBySessionId(Long sessionId);
}
