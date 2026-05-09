package com.josh.interviewj.billing.activation.repository;

import com.josh.interviewj.billing.activation.model.ActivationCode;
import com.josh.interviewj.billing.activation.model.ActivationCodeStatus;
import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import com.josh.interviewj.billing.repository.BillingRepositoryIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ActivationCodeRepositoryTest.TestActivationCodeJpaApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class ActivationCodeRepositoryTest extends BillingRepositoryIntegrationTestBase {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRESQL = POSTGRES;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActivationCodeRepository activationCodeRepository;

    private Long createdByUserId;

    @BeforeEach
    void setUp() {
        createdByUserId = insertUser("activation-expiration-user-" + UUID.randomUUID(), "activation-expiration@example.com");
    }

    @Test
    void expireOverdueCodesUpdatesStatusAndUpdatedAt() {
        String code = "SUBA2B3C4D5";
        LocalDateTime previousUpdatedAt = LocalDateTime.of(2026, 4, 2, 9, 0);
        LocalDateTime now = LocalDateTime.of(2026, 4, 3, 10, 0);
        jdbcTemplate.update("""
                        INSERT INTO activation_code (
                            code, code_type, status, expires_at, created_by_user_id, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                code,
                "SUBSCRIPTION",
                "UNUSED",
                LocalDateTime.of(2026, 4, 2, 8, 0),
                createdByUserId,
                LocalDateTime.of(2026, 4, 2, 7, 0),
                previousUpdatedAt
        );

        int updatedRows = activationCodeRepository.expireOverdueCodes(now);

        assertThat(updatedRows).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, updated_at FROM activation_code WHERE code = ?",
                code
        );
        assertThat(row.get("status")).isEqualTo("EXPIRED");
        assertThat(toLocalDateTime(row.get("updated_at"))).isEqualTo(now);
    }

    @Test
    void findByFilters_WhenCreatedFromAndCreatedToAreNull_DoesNotFailParameterTypeInference() {
        insertActivationCode("SUBA1111111", LocalDateTime.of(2026, 4, 1, 10, 0));
        insertActivationCode("SUBA2222222", LocalDateTime.of(2026, 4, 2, 10, 0));

        Page<ActivationCode> result = activationCodeRepository.findByFilters(
                ActivationCodeStatus.UNUSED,
                ActivationCodeType.SUBSCRIPTION,
                null,
                createdByUserId,
                null,
                null,
                PageRequest.of(0, 10)
        );

        assertThat(result.getContent())
                .extracting(ActivationCode::getCode)
                .containsExactly("SUBA2222222", "SUBA1111111");
    }

    private Long insertUser(String username, String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash, locale, timezone)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, username, email, "hashed", "zh-CN", "Asia/Shanghai");
    }

    private void insertActivationCode(String code, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                        INSERT INTO activation_code (
                            code, code_type, status, created_by_user_id, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                code,
                "SUBSCRIPTION",
                "UNUSED",
                createdByUserId,
                createdAt,
                createdAt
        );
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        throw new IllegalStateException("Unsupported updated_at value type: " + value);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ActivationCode.class)
    @EnableJpaRepositories(basePackageClasses = ActivationCodeRepository.class)
    static class TestActivationCodeJpaApplication {
    }
}
