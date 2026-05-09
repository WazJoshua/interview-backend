package com.josh.interviewj.usage.repository;

import com.josh.interviewj.usage.model.BillingUnit;
import com.josh.interviewj.usage.model.LlmConfigChangeOutbox;
import com.josh.interviewj.usage.model.LlmConfigVersion;
import com.josh.interviewj.usage.model.LlmModelCatalog;
import com.josh.interviewj.usage.model.LlmModelPricingVersion;
import com.josh.interviewj.usage.model.LlmProvider;
import com.josh.interviewj.usage.model.LlmProviderSecret;
import com.josh.interviewj.usage.model.LlmRoutingPolicy;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.support.UsageIntegrationTestBase;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LlmModelPricingVersionRepositoryTest.TestUsageJpaApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class LlmModelPricingVersionRepositoryTest extends UsageIntegrationTestBase {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRESQL = POSTGRES;

    @Container
    static final RedisContainer REDIS_CONTAINER = REDIS;

    @Autowired
    private LlmProviderRepository llmProviderRepository;

    @Autowired
    private LlmModelCatalogRepository llmModelCatalogRepository;

    @Autowired
    private LlmModelPricingVersionRepository llmModelPricingVersionRepository;

    @Test
    void findActiveVersionByModelId_ResolvesNewestActiveWindow() {
        LlmProvider provider = llmProviderRepository.saveAndFlush(provider("pricing-provider"));
        LlmModelCatalog model = llmModelCatalogRepository.saveAndFlush(model(provider, "pricing-model"));
        llmModelPricingVersionRepository.saveAndFlush(pricing(model, LocalDateTime.of(2026, 4, 1, 0, 0), LocalDateTime.of(2026, 5, 1, 0, 0)));
        LlmModelPricingVersion active = llmModelPricingVersionRepository.saveAndFlush(
                pricing(model, LocalDateTime.of(2026, 5, 1, 0, 0), null)
        );

        assertThat(llmModelPricingVersionRepository.findActiveVersionByModelId(model.getId(), LocalDateTime.of(2026, 5, 10, 0, 0)))
                .map(LlmModelPricingVersion::getId)
                .contains(active.getId());
    }

    @Test
    void findOverlappingVersionsByModelId_FindsOverlapWithinSameModelScope() {
        LlmProvider provider = llmProviderRepository.saveAndFlush(provider("pricing-provider-overlap"));
        LlmModelCatalog model = llmModelCatalogRepository.saveAndFlush(model(provider, "pricing-model-overlap"));
        LlmModelPricingVersion version = llmModelPricingVersionRepository.saveAndFlush(
                pricing(model, LocalDateTime.of(2026, 5, 1, 0, 0), null)
        );

        assertThat(llmModelPricingVersionRepository.findOverlappingVersionsByModelId(
                model.getId(),
                LocalDateTime.of(2026, 5, 10, 0, 0),
                null,
                null
        )).extracting(LlmModelPricingVersion::getId).containsExactly(version.getId());
    }

    private LlmProvider provider(String providerKey) {
        return LlmProvider.builder()
                .providerKey(providerKey)
                .displayName("Display " + providerKey)
                .baseUrl("https://provider.example.com/v1")
                .enabled(true)
                .supportedUsageFamilies("[\"CHAT\"]")
                .build();
    }

    private LlmModelCatalog model(LlmProvider provider, String modelCode) {
        return LlmModelCatalog.builder()
                .provider(provider.getProviderKey())
                .providerRef(provider)
                .modelCode(modelCode)
                .usageFamily(UsageFamily.CHAT)
                .displayName("Display " + modelCode)
                .active(true)
                .build();
    }

    private LlmModelPricingVersion pricing(LlmModelCatalog model, LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
        return LlmModelPricingVersion.builder()
                .provider(model.getProvider())
                .modelCode(model.getModelCode())
                .usageFamily(model.getUsageFamily())
                .modelRef(model)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .billingUnit(BillingUnit.TOKEN)
                .promptTokenPrice(new BigDecimal("0.000010"))
                .currency("USD")
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {
            LlmProvider.class,
            LlmProviderSecret.class,
            LlmRoutingPolicy.class,
            LlmConfigVersion.class,
            LlmConfigChangeOutbox.class,
            LlmModelCatalog.class,
            LlmModelPricingVersion.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            LlmProviderRepository.class,
            LlmProviderSecretRepository.class,
            LlmRoutingPolicyRepository.class,
            LlmConfigVersionRepository.class,
            LlmConfigChangeOutboxRepository.class,
            LlmModelCatalogRepository.class,
            LlmModelPricingVersionRepository.class
    })
    static class TestUsageJpaApplication {
    }
}
