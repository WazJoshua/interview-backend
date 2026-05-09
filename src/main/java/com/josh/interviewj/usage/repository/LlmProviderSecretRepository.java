package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.LlmProviderSecret;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LlmProviderSecretRepository extends JpaRepository<LlmProviderSecret, Long> {

    List<LlmProviderSecret> findByProvider_IdIn(Collection<Long> providerIds);

    Optional<LlmProviderSecret> findByProvider_Id(Long providerId);
}
