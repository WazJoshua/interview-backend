package com.josh.interviewj.usage.repository;

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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = LlmProviderRepositoryTest.TestUsageJpaApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class LlmProviderRepositoryTest extends UsageIntegrationTestBase {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRESQL = POSTGRES;

    @Container
    static final RedisContainer REDIS_CONTAINER = REDIS;

    @Autowired
    private LlmProviderRepository llmProviderRepository;

    @Test
    void save_WhenProviderKeyDuplicated_RejectsDuplicate() {
        llmProviderRepository.saveAndFlush(provider("provider-dup", null));

        assertThatThrownBy(() -> llmProviderRepository.saveAndFlush(provider("provider-dup", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findAllAndFindByProviderKey_DefaultsToNonDeletedProviders() {
        LlmProvider activeProvider = llmProviderRepository.saveAndFlush(provider("provider-active", null));
        llmProviderRepository.saveAndFlush(provider("provider-deleted", LocalDateTime.of(2026, 4, 7, 0, 0)));

        assertThat(llmProviderRepository.findAll())
                .extracting(LlmProvider::getProviderKey)
                .containsExactly(activeProvider.getProviderKey());
        assertThat(llmProviderRepository.findByProviderKey("provider-active"))
                .map(LlmProvider::getProviderKey)
                .contains("provider-active");
        assertThat(llmProviderRepository.findByProviderKey("provider-deleted")).isEmpty();
    }

    private LlmProvider provider(String providerKey, LocalDateTime deletedAt) {
        return LlmProvider.builder()
                .providerKey(providerKey)
                .displayName("Display " + providerKey)
                .baseUrl("https://provider.example.com/v1")
                .enabled(true)
                .deletedAt(deletedAt)
                .supportedUsageFamilies("[\"CHAT\"]")
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
