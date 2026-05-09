package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.LlmRoutingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LlmRoutingPolicyRepository extends JpaRepository<LlmRoutingPolicy, Long> {

    Optional<LlmRoutingPolicy> findByPurpose(String purpose);

    @Query("""
            SELECT DISTINCT policy
            FROM LlmRoutingPolicy policy
            JOIN FETCH policy.model model
            LEFT JOIN FETCH model.providerRef
            WHERE policy.purpose = :purpose
            """)
    Optional<LlmRoutingPolicy> findByPurposeWithModelAndProvider(String purpose);

    @Query("""
            SELECT DISTINCT policy
            FROM LlmRoutingPolicy policy
            JOIN FETCH policy.model model
            LEFT JOIN FETCH model.providerRef
            ORDER BY policy.purpose ASC
            """)
    List<LlmRoutingPolicy> findAllWithModelAndProvider();
}
