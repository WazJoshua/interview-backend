package com.josh.interviewj.usage.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class UsageIntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("interviewj_usage")
            .withUsername("test")
            .withPassword("test");

    protected static final RedisContainer REDIS = new RedisContainer("redis:7.4.7");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
        registry.add("spring.task.scheduling.enabled", () -> "false");
        registry.add("app.scheduling.enabled", () -> "false");
        registry.add("app.redis-stream.listeners-enabled", () -> "false");
        registry.add("app.mq.listeners-enabled", () -> "false");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> "false");
        registry.add("jwt.secret", () -> "test-secret-key-for-usage-integration-tests");
        registry.add("app.interview.timeout.enabled", () -> "false");
        registry.add("app.llm.routing.purposes.parse.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.parse.provider", () -> "default");
        registry.add("app.llm.routing.purposes.analysis.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.analysis.provider", () -> "default");
        registry.add("app.llm.routing.purposes.rag.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.rag.provider", () -> "default");
        registry.add("app.llm.providers.default.base-url", () -> "https://provider.example.com/v1");
        registry.add("app.llm.providers.default.api-key", () -> "test-key");
        registry.add("app.llm.providers.default.chat.models.parse", () -> "test-parse-model");
        registry.add("app.llm.providers.default.chat.models.analysis", () -> "test-analysis-model");
        registry.add("app.llm.providers.default.chat.models.rag", () -> "test-rag-model");
    }
}
