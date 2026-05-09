package com.josh.interviewj.admin.repository;

import com.josh.interviewj.admin.model.AdminOperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminOperationLogRepository extends JpaRepository<AdminOperationLog, Long> {
}
