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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = LlmRoutingPolicyRepositoryTest.TestUsageJpaApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class LlmRoutingPolicyRepositoryTest extends UsageIntegrationTestBase {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRESQL = POSTGRES;

    @Container
    static final RedisContainer REDIS_CONTAINER = REDIS;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LlmProviderRepository llmProviderRepository;

    @Autowired
    private LlmModelCatalogRepository llmModelCatalogRepository;

    @Autowired
    private LlmModelPricingVersionRepository llmModelPricingVersionRepository;

    @Autowired
    private LlmRoutingPolicyRepository llmRoutingPolicyRepository;

    @Test
    void save_WhenPurposeDuplicated_RejectsDuplicatePurpose() {
        LlmProvider provider = llmProviderRepository.saveAndFlush(provider("routing-provider"));
        LlmModelCatalog firstModel = llmModelCatalogRepository.saveAndFlush(model(provider, "model-a", UsageFamily.CHAT));
        LlmModelCatalog secondModel = llmModelCatalogRepository.saveAndFlush(model(provider, "model-b", UsageFamily.CHAT));
        llmRoutingPolicyRepository.saveAndFlush(routing("analysis", firstModel));

        assertThatThrownBy(() -> llmRoutingPolicyRepository.saveAndFlush(routing("analysis", secondModel)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_ModelAndPricingPersistsNewForeignKeysAndRoutingIsQueryableByPurpose() {
        LlmProvider provider = llmProviderRepository.saveAndFlush(provider("routing-provider-query"));
        LlmModelCatalog model = llmModelCatalogRepository.saveAndFlush(model(provider, "model-query", UsageFamily.CHAT));
        LlmModelPricingVersion pricingVersion = llmModelPricingVersionRepository.saveAndFlush(
                LlmModelPricingVersion.builder()
                        .provider(provider.getProviderKey())
                        .modelCode(model.getModelCode())
                        .usageFamily(UsageFamily.CHAT)
                        .modelRef(model)
                        .effectiveFrom(LocalDateTime.of(2026, 4, 7, 0, 0))
                        .billingUnit(BillingUnit.TOKEN)
                        .promptTokenPrice(new BigDecimal("0.000010"))
                        .currency("USD")
                        .build()
        );
        llmRoutingPolicyRepository.saveAndFlush(routing("analysis-query", model));

        assertThat(llmRoutingPolicyRepository.findByPurpose("analysis-query"))
                .map(LlmRoutingPolicy::getModel)
                .map(LlmModelCatalog::getId)
                .contains(model.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_id FROM llm_model_catalog WHERE id = ?",
                Long.class,
                model.getId()
        )).isEqualTo(provider.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT model_id FROM llm_model_pricing_version WHERE id = ?",
                Long.class,
                pricingVersion.getId()
        )).isEqualTo(model.getId());
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

    private LlmModelCatalog model(LlmProvider provider, String modelCode, UsageFamily usageFamily) {
        return LlmModelCatalog.builder()
                .provider(provider.getProviderKey())
                .providerRef(provider)
                .modelCode(modelCode)
                .usageFamily(usageFamily)
                .displayName("Display " + modelCode)
                .active(true)
                .build();
    }

    private LlmRoutingPolicy routing(String purpose, LlmModelCatalog model) {
        return LlmRoutingPolicy.builder()
                .purpose(purpose)
                .model(model)
                .enabled(true)
                .timeoutMs(30_000)
                .maxRetries(2)
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
