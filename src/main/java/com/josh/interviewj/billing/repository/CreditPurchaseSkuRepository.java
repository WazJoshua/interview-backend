package com.josh.interviewj.billing.repository;

import com.josh.interviewj.billing.model.CreditPurchaseSku;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreditPurchaseSkuRepository extends JpaRepository<CreditPurchaseSku, Long> {

    Optional<CreditPurchaseSku> findByExternalId(UUID externalId);

    Optional<CreditPurchaseSku> findBySkuCode(String skuCode);
}
