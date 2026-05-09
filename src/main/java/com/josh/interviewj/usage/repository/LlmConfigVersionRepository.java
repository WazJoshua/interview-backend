package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.LlmConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmConfigVersionRepository extends JpaRepository<LlmConfigVersion, String> {
}
