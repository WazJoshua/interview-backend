package com.josh.interviewj;

import com.josh.interviewj.auth.dto.request.PasswordEnvelope;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.redis.testcontainers.RedisContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

/**
 * Shared Testcontainers setup for integration tests.
 */
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationTestBase {

    protected static final String TEST_PASSWORD_KEY_ID = "pwd-key-integration";
    private static final KeyPair PASSWORD_KEY_PAIR = generatePasswordKeyPair();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("interviewj")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final RedisContainer REDIS = new RedisContainer("redis:7.4.7");

    /**
     * Register container endpoints into Spring Boot test properties.
     *
     * @param registry dynamic property registry
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
        registry.add("app.auth.password-encryption.active-key-id", () -> TEST_PASSWORD_KEY_ID);
        registry.add("app.auth.password-encryption.active-private-key-pem", IntegrationTestBase::privateKeyPem);
        registry.add("app.auth.password-reset.notification-mode", () -> "local-file");

        // Keep integration tests deterministic: scheduled jobs are invoked explicitly when needed.
        registry.add("spring.task.scheduling.enabled", () -> "false");
        registry.add("app.scheduling.enabled", () -> "false");
        registry.add("app.redis-stream.listeners-enabled", () -> "false");
        registry.add("app.llm.secret.current-key-version", () -> "current");
        registry.add("app.llm.secret.master-keys.current", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("app.llm.secret.master-keys.previous", () -> "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");

        // JWT configuration for integration tests
        registry.add("jwt.secret", () -> "test-secret-key-for-integration-tests-must-be-long-enough");

        // RabbitMQ topology and runtime configuration for integration tests
        registry.add("app.mq.listeners-enabled", () -> "false");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> "false");
        registry.add("app.mq.resume-parse.exchange", () -> "resume.parse.exchange");
        registry.add("app.mq.resume-parse.queue", () -> "resume.parse.queue");
        registry.add("app.mq.resume-parse.routing-key", () -> "resume.parse");
        registry.add("app.mq.resume-parse.dlx", () -> "resume.parse.dlx");
        registry.add("app.mq.resume-parse.dlq", () -> "resume.parse.dlq");
        registry.add("app.mq.resume-parse.dlq-routing-key", () -> "resume.parse.dlq");
        registry.add("app.mq.resume-parse.message-timeout", () -> "180000");
        registry.add("app.mq.resume-parse.max-retries", () -> "3");
        registry.add("app.mq.resume-parse.heartbeat-interval", () -> "10000");
        registry.add("app.mq.resume-parse.idempotent-cache-ttl", () -> "86400000");
        registry.add("app.mq.kb-doc.exchange", () -> "kb.doc.exchange");
        registry.add("app.mq.kb-doc.queue", () -> "kb.doc.queue");
        registry.add("app.mq.kb-doc.routing-key", () -> "kb.doc");
        registry.add("app.mq.kb-doc.dlx", () -> "kb.doc.dlx");
        registry.add("app.mq.kb-doc.dlq", () -> "kb.doc.dlq");
        registry.add("app.mq.kb-doc.dlq-routing-key", () -> "kb.doc.dlq");
        registry.add("app.mq.kb-doc.message-timeout", () -> "300000");
        registry.add("app.mq.kb-doc.max-retries", () -> "3");
        registry.add("app.mq.kb-doc.heartbeat-interval", () -> "15000");
        registry.add("app.mq.kb-doc.idempotent-cache-ttl", () -> "7200000");
        registry.add("app.mq.resume-analysis.exchange", () -> "resume.analysis.exchange");
        registry.add("app.mq.resume-analysis.queue", () -> "resume.analysis.queue");
        registry.add("app.mq.resume-analysis.routing-key", () -> "resume.analysis");
        registry.add("app.mq.resume-analysis.dlx", () -> "resume.analysis.dlx");
        registry.add("app.mq.resume-analysis.dlq", () -> "resume.analysis.dlq");
        registry.add("app.mq.resume-analysis.dlq-routing-key", () -> "resume.analysis.dlq");
        registry.add("app.mq.resume-analysis.message-timeout", () -> "300000");
        registry.add("app.mq.resume-analysis.heartbeat-interval", () -> "15000");
        registry.add("app.mq.resume-analysis.idempotent-cache-ttl", () -> "86400000");
        registry.add("app.outbox.max-retries", () -> "5");
        registry.add("app.outbox.processing-timeout", () -> "30000");

        // LLM configuration for integration tests - uses mock LLMService in most tests
        registry.add("app.llm.providers.primary_chat.base-url", () -> "https://test.example.com/v1");
        registry.add("app.llm.providers.primary_chat.api-key", () -> "test-api-key");
        registry.add("app.llm.providers.primary_chat.chat.models.parse", () -> "test-parse-model");
        registry.add("app.llm.providers.primary_chat.chat.models.analysis", () -> "test-analysis-model");
        registry.add("app.llm.providers.primary_chat.chat.models.rag", () -> "test-rag-model");
        registry.add("app.llm.routing.purposes.parse.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.parse.provider", () -> "primary_chat");
        registry.add("app.llm.routing.purposes.analysis.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.analysis.provider", () -> "primary_chat");
        registry.add("app.llm.routing.purposes.rag.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.rag.provider", () -> "primary_chat");
        // Interview LLM purposes
        registry.add("app.llm.routing.purposes.interview_question_generation.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.interview_question_generation.provider", () -> "primary_chat");
        registry.add("app.llm.routing.purposes.interview_follow_up_generation.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.interview_follow_up_generation.provider", () -> "primary_chat");
        registry.add("app.llm.routing.purposes.interview_answer_evaluation.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.interview_answer_evaluation.provider", () -> "primary_chat");
        registry.add("app.llm.routing.purposes.interview_report_generation.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.interview_report_generation.provider", () -> "primary_chat");
        // KB LLM purposes
        registry.add("app.llm.routing.purposes.kb_query_rewrite.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.kb_query_rewrite.provider", () -> "primary_chat");
        registry.add("app.llm.routing.purposes.kb_query_embedding.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.kb_query_embedding.provider", () -> "embedding_provider");
        registry.add("app.llm.routing.purposes.kb_document_embedding.strategy", () -> "single");
        registry.add("app.llm.routing.purposes.kb_document_embedding.provider", () -> "embedding_provider");
        // Embedding provider
        registry.add("app.llm.providers.embedding_provider.base-url", () -> "https://test-embedding.example.com/v1");
        registry.add("app.llm.providers.embedding_provider.api-key", () -> "test-embedding-key");
        registry.add("app.llm.providers.embedding_provider.embedding.dimension", () -> "2048");
        registry.add("app.llm.providers.embedding_provider.embedding.models.kb_query_embedding", () -> "test-query-embedding-model");
        registry.add("app.llm.providers.embedding_provider.embedding.models.kb_document_embedding", () -> "test-document-embedding-model");
    }

    protected void upsertChunkWithDefaultSparseMaterialization(
            DocumentChunkRepository documentChunkRepository,
            Long documentId,
            Long kbId,
            String content,
            Integer chunkIndex,
            Integer startPosition,
            Integer endPosition,
            Integer tokenCount,
            String metadata
    ) {
        documentChunkRepository.upsertChunk(
                documentId,
                kbId,
                content,
                chunkIndex,
                startPosition,
                endPosition,
                tokenCount,
                metadata,
                content,
                content,
                ""
        );
    }

    protected PasswordEnvelope passwordEnvelope(String plaintext) {
        return passwordEnvelope(plaintext, UUID.randomUUID().toString());
    }

    protected PasswordEnvelope passwordEnvelope(String plaintext, String nonce) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.ENCRYPT_MODE, PASSWORD_KEY_PAIR.getPublic(), oaepSha256ParameterSpec());
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            PasswordEnvelope envelope = new PasswordEnvelope();
            envelope.setKeyId(TEST_PASSWORD_KEY_ID);
            envelope.setNonce(nonce);
            envelope.setTimestamp(System.currentTimeMillis());
            envelope.setCiphertext(Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext));
            return envelope;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt password for integration test", ex);
        }
    }

    protected LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    private OAEPParameterSpec oaepSha256ParameterSpec() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    private static KeyPair generatePasswordKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to generate integration password key pair", ex);
        }
    }

    private static String privateKeyPem() {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(PASSWORD_KEY_PAIR.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
    }
}

