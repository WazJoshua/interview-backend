package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.LlmProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LlmProviderRepository extends JpaRepository<LlmProvider, Long> {

    Optional<LlmProvider> findByProviderKey(String providerKey);

    List<LlmProvider> findAllByOrderByProviderKeyAsc();
}
