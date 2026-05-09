package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.LlmConfigChangeOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LlmConfigChangeOutboxRepository extends JpaRepository<LlmConfigChangeOutbox, Long> {

    List<LlmConfigChangeOutbox> findTop100ByPublishStatusOrderByCreatedAtAscIdAsc(String publishStatus);
}
