package com.josh.interviewj.kb;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.model.CreditWallet;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.common.mq.message.KbDocumentMessage;
import com.josh.interviewj.knowledgebase.consumer.KbDocumentConsumer;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.knowledgebase.service.KbDocumentIngestionService;
import com.josh.interviewj.knowledgebase.service.KbDocumentOutboxPublisherService;
import com.josh.interviewj.knowledgebase.service.DocumentEmbeddingService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseStorageService;
import com.josh.interviewj.knowledgebase.model.DocumentChunk;
import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.resume.service.DocumentParserService;
import com.josh.interviewj.usage.repository.UsageRejectionRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class KbDocumentPipelineIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private KbDocumentRepository kbDocumentRepository;

    @Autowired
    private KbDocumentOutboxRepository kbDocumentOutboxRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private KbDocumentOutboxPublisherService publisherService;

    @Autowired
    private KbDocumentIngestionService kbDocumentIngestionService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CreditWalletRepository creditWalletRepository;

    @Autowired
    private UsageRejectionRecordRepository usageRejectionRecordRepository;

    @MockitoBean
    private DocumentParserService documentParserService;

    @MockitoBean
    private KnowledgeBaseStorageService knowledgeBaseStorageService;

    @MockitoBean
    private DocumentEmbeddingService documentEmbeddingService;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private KbDocumentConsumer kbDocumentConsumer;

    @Autowired
    private DocumentPreprocessingProperties preprocessingProperties;

    @BeforeEach
    void setUp() {
        stubPublishSuccess();
        documentChunkRepository.deleteAllInBatch();
        kbDocumentOutboxRepository.deleteAllInBatch();
        kbDocumentRepository.deleteAllInBatch();
        knowledgeBaseRepository.deleteAllInBatch();
        usageRejectionRecordRepository.deleteAllInBatch();
        creditWalletRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        resetKbDocRedisState();
    }

    @Test
    void publishThenConsume_HappyPath_CompletesDocumentAndWritesIdempotentKey() throws java.io.IOException {
        TestFixture fixture = createFixture("pipeline-owner", "pipeline-owner@example.com", "pipeline.txt", "text/plain");

        when(knowledgeBaseStorageService.getFilePath("mock://pipeline.txt")).thenReturn(Path.of("pipeline.txt"));
        when(documentParserService.extractText(Path.of("pipeline.txt"), "text/plain")).thenReturn("a".repeat(1300));
        when(documentEmbeddingService.embedDocumentWithUsage(any(), anyString(), anyString()))
                .thenAnswer(invocation -> embeddingExecution(invocation.getArgument(1, String.class), invocation.getArgument(2, String.class)));

        publisherService.publishPendingOutboxMessages();
        kbDocumentConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        KbDocument completed = kbDocumentRepository.findById(fixture.document().getId()).orElseThrow();
        assertEquals(KbDocumentStatus.COMPLETED, completed.getStatus());
        assertEquals(2, completed.getExpectedChunkCount());
        assertEquals(2, completed.getEmbeddedChunkCount());
        assertEquals(2, completed.getChunkCount());
        assertNotNull(completed.getProcessedAt());
        assertEquals(2, documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(fixture.document().getId()).size());
        assertEquals(2, documentChunkRepository.countEmbeddedChunks(fixture.document().getId()));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey("kb:doc:processed:" + fixture.outbox().getId())));

        KbDocumentOutbox sentOutbox = kbDocumentOutboxRepository.findById(fixture.outbox().getId()).orElseThrow();
        assertEquals(OutboxStatus.SENT, sentOutbox.getStatus());
        assertNotNull(sentOutbox.getSentAt());
    }

    @Test
    void publishThenConsume_RetryableFailure_PreservesCompletedChunksAndSecondRunCompletes() throws java.io.IOException {
        TestFixture fixture = createFixture("retry-owner", "retry-owner@example.com", "retry.txt", "text/plain");

        when(knowledgeBaseStorageService.getFilePath("mock://retry.txt")).thenReturn(Path.of("retry.txt"));
        when(documentParserService.extractText(Path.of("retry.txt"), "text/plain")).thenReturn("a".repeat(2401));
        when(documentEmbeddingService.embedDocumentWithUsage(any(), anyString(), anyString()))
                .thenAnswer(invocation -> embeddingExecution(invocation.getArgument(1, String.class), invocation.getArgument(2, String.class)))
                .thenThrow(new RuntimeException("retry-me"));

        publisherService.publishPendingOutboxMessages();
        kbDocumentConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        KbDocument retried = kbDocumentRepository.findById(fixture.document().getId()).orElseThrow();
        assertEquals(KbDocumentStatus.PENDING, retried.getStatus());
        assertEquals(3, retried.getExpectedChunkCount());
        assertEquals(1, documentChunkRepository.countEmbeddedChunks(fixture.document().getId()));
        assertTrue(!Boolean.TRUE.equals(stringRedisTemplate.hasKey("kb:doc:processed:" + fixture.outbox().getId())));

        KbDocumentOutbox retryOutbox = kbDocumentOutboxRepository.findAll().stream()
                .filter(item -> item.getDocumentId().equals(fixture.document().getId()))
                .filter(item -> item.getStatus() == OutboxStatus.NEW)
                .filter(item -> !item.getId().equals(fixture.outbox().getId()))
                .findFirst()
                .orElseThrow();

        org.mockito.Mockito.reset(documentEmbeddingService);
        when(documentEmbeddingService.embedDocumentWithUsage(any(), anyString(), anyString()))
                .thenAnswer(invocation -> embeddingExecution(invocation.getArgument(1, String.class), invocation.getArgument(2, String.class)));

        publisherService.publishPendingOutboxMessages();
        kbDocumentConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        KbDocument completed = kbDocumentRepository.findById(fixture.document().getId()).orElseThrow();
        assertEquals(KbDocumentStatus.COMPLETED, completed.getStatus());
        assertEquals(3, completed.getExpectedChunkCount());
        assertEquals(3, completed.getEmbeddedChunkCount());
        assertEquals(3, completed.getChunkCount());
        assertEquals(3, documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(fixture.document().getId()).size());
        assertEquals(3, documentChunkRepository.countEmbeddedChunks(fixture.document().getId()));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey("kb:doc:processed:" + retryOutbox.getId())));
    }

    @Test
    void redeliveryAfterStateReset_CompletesDocument() throws java.io.IOException {
        TestFixture fixture = createFixture("recover-owner", "recover-owner@example.com", "recover.txt", "text/plain");

        when(knowledgeBaseStorageService.getFilePath("mock://recover.txt")).thenReturn(Path.of("recover.txt"));
        when(documentParserService.extractText(Path.of("recover.txt"), "text/plain")).thenReturn("a".repeat(1300));
        when(documentEmbeddingService.embedDocumentWithUsage(any(), anyString(), anyString()))
                .thenAnswer(invocation -> embeddingExecution(invocation.getArgument(1, String.class), invocation.getArgument(2, String.class)));

        publisherService.publishPendingOutboxMessages();
        jdbcTemplate.update(
                "UPDATE kb_documents SET status = ?, updated_at = ? WHERE id = ?",
                KbDocumentStatus.PENDING.name(),
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(10)),
                fixture.document().getId()
        );
        kbDocumentConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        KbDocument completed = kbDocumentRepository.findById(fixture.document().getId()).orElseThrow();
        assertEquals(KbDocumentStatus.COMPLETED, completed.getStatus());
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey("kb:doc:processed:" + fixture.outbox().getId())));
    }

    @Test
    void publishThenConsume_DocumentLevelClaimTakeover_ResumesFromUnfinishedChunks() throws java.io.IOException {
        TestFixture fixture = createFixture("takeover-owner", "takeover-owner@example.com", "takeover.txt", "text/plain");
        String firstChunkContent = "a".repeat(1200);

        when(knowledgeBaseStorageService.getFilePath("mock://takeover.txt")).thenReturn(Path.of("takeover.txt"));
        when(documentParserService.extractText(Path.of("takeover.txt"), "text/plain")).thenReturn("a".repeat(2401));
        when(documentEmbeddingService.embedDocumentWithUsage(any(), anyString(), anyString()))
                .thenAnswer(invocation -> embeddingExecution(invocation.getArgument(1, String.class), invocation.getArgument(2, String.class)));

        upsertChunkWithDefaultSparseMaterialization(
                documentChunkRepository,
                fixture.document().getId(),
                fixture.knowledgeBase().getId(),
                firstChunkContent,
                0,
                0,
                1200,
                1200,
                "{}"
        );
        documentChunkRepository.updateEmbeddingIfNull(fixture.document().getId(), 0, vectorLiteral(2048));
        jdbcTemplate.update(
                "UPDATE kb_documents SET status = ?, chunk_count = ?, embedded_chunk_count = ?, expected_chunk_count = ?, updated_at = ? WHERE id = ?",
                KbDocumentStatus.PROCESSING.name(),
                1,
                1,
                3,
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(20)),
                fixture.document().getId()
        );

        publisherService.publishPendingOutboxMessages();
        kbDocumentConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

        KbDocument completed = kbDocumentRepository.findById(fixture.document().getId()).orElseThrow();
        assertEquals(KbDocumentStatus.COMPLETED, completed.getStatus());
        assertEquals(3, completed.getExpectedChunkCount());
        assertEquals(3, completed.getEmbeddedChunkCount());
        assertEquals(3, completed.getChunkCount());
        assertEquals(3, documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(fixture.document().getId()).size());
        assertEquals(3, documentChunkRepository.countEmbeddedChunks(fixture.document().getId()));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey("kb:doc:processed:" + fixture.outbox().getId())));
        verify(documentEmbeddingService, times(2)).embedDocumentWithUsage(any(), anyString(), anyString());
    }

    private TestFixture createFixture(String username, String email, String fileName, String fileType) {
        User owner = userRepository.save(User.builder()
                .username(username)
                .email(email)
                .password("hashed")
                .build());
        grantCredits(owner.getId());

        KnowledgeBase knowledgeBase = knowledgeBaseRepository.save(KnowledgeBase.builder()
                .userId(owner.getId())
                .name("Pipeline KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .build());

        KbDocument document = kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName(fileName)
                .fileType(fileType)
                .fileUrl("mock://" + fileName)
                .status(KbDocumentStatus.PENDING)
                .build());

        KbDocumentOutbox outbox = kbDocumentOutboxRepository.save(KbDocumentOutbox.builder()
                .kbId(knowledgeBase.getId())
                .documentId(document.getId())
                .status(OutboxStatus.NEW)
                .retryCount(0)
                .build());

        return new TestFixture(owner, knowledgeBase, document, outbox);
    }

    private KbDocumentMessage readLastMessage() {
        KbDocumentOutbox outbox = kbDocumentOutboxRepository.findAll().stream()
                .max(java.util.Comparator.comparing(KbDocumentOutbox::getId))
                .orElseThrow();
        KbDocument document = kbDocumentRepository.findById(outbox.getDocumentId()).orElseThrow();
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(outbox.getKbId()).orElseThrow();
        return new KbDocumentMessage(
                knowledgeBase.getId(),
                knowledgeBase.getExternalId(),
                document.getId(),
                document.getExternalId(),
                outbox.getId()
        );
    }

    private void resetKbDocRedisState() {
        Set<String> processedKeys = stringRedisTemplate.keys("kb:doc:processed:*");
        if (processedKeys != null && !processedKeys.isEmpty()) {
            stringRedisTemplate.delete(processedKeys);
        }
    }

    private float[] vector(int dimensions) {
        float[] vector = new float[dimensions];
        vector[0] = 1.0F;
        return vector;
    }

    private String vectorLiteral(int dimensions) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < dimensions; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(index == 0 ? "1.0" : "0.0");
        }
        builder.append(']');
        return builder.toString();
    }

    private record TestFixture(User owner, KnowledgeBase knowledgeBase, KbDocument document, KbDocumentOutbox outbox) {
    }

    private void grantCredits(Long userId) {
        creditWalletRepository.save(CreditWallet.builder()
                .userId(userId)
                .purchasedBalanceMicros(1_000_000L)
                .build());
    }

    private DocumentEmbeddingService.EmbeddingExecutionResult embeddingExecution(String invocationId, String content) {
        EmbeddingResponse response = new EmbeddingResponse(vector(2048), "mock-provider", "mock-model");
        AiInvocationContext invocationContext = new AiInvocationContext(
                invocationId,
                "kb_document_embedding",
                com.josh.interviewj.usage.model.UsageFamily.EMBEDDING,
                "KB_INGESTION_CREDITS",
                false,
                Map.of("contentLength", content == null ? 0 : content.length())
        );
        return new DocumentEmbeddingService.EmbeddingExecutionResult(
                response,
                invocationContext,
                AiInvocationResult.fromEmbedding(response)
        );
    }

    private void stubPublishSuccess() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    // ========================================
    // Structure-Aware Ingestion E2E Tests
    // ========================================

    @Nested
    class StructureAwareIngestionE2E {

        /**
         * Tests structure-aware ingestion path with Markdown file.
         * Verifies:
         * 1. Chunks are created with displayText/embeddingText separation
         * 2. Metadata contract includes required fields
         * 3. deleteByDocumentId rebuild cleans old chunks
         */
        @Test
        void structureAwareIngestion_Markdown_CreatesChunksWithContextSeparation() throws Exception {
            // Enable structure-aware mode for this test
            preprocessingProperties.getChunking().setStructureAwareEnabled(true);
            // Given: Markdown document with structure
            String markdownContent = """
                    # API Reference

                    This document describes the API endpoints.

                    ## Authentication

                    Use the following endpoints for authentication:

                    - POST /auth/login
                    - POST /auth/logout

                    ## Configuration

                    | Variable | Default | Description |
                    |----------|---------|-------------|
                    | API_URL | localhost | API endpoint |
                    | TIMEOUT | 30000 | Timeout in ms |
                    """;

            Path tempFile = Files.createTempFile("kb-structure-aware-", ".md");
            Files.writeString(tempFile, markdownContent);

            TestFixture fixture = createTestFixtureForStructureAware("structure-owner", "structure@example.com", tempFile);

            when(knowledgeBaseStorageService.getFilePath(fixture.document().getFileUrl())).thenReturn(tempFile);
            when(documentEmbeddingService.embedDocumentWithUsage(any(), anyString(), anyString()))
                    .thenAnswer(invocation -> embeddingExecution(invocation.getArgument(1, String.class), invocation.getArgument(2, String.class)));

            // When: Process the document
            publisherService.publishPendingOutboxMessages();
            kbDocumentConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

            // Then: Verify structure-aware ingestion
            KbDocument completed = kbDocumentRepository.findById(fixture.document().getId()).orElseThrow();
            assertEquals(KbDocumentStatus.COMPLETED, completed.getStatus());

            List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(fixture.document().getId());
            assertTrue(chunks.size() > 0, "Should have chunks");

            org.mockito.ArgumentCaptor<String> embeddingCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(documentEmbeddingService, atLeastOnce()).embedDocumentWithUsage(any(), anyString(), embeddingCaptor.capture());
            List<String> embeddingInputs = embeddingCaptor.getAllValues();
            List<String> storedContents = chunks.stream().map(DocumentChunk::getContent).toList();

            assertTrue(
                    embeddingInputs.stream().anyMatch(input -> input.contains("[文档]") || input.contains("[章节]") || input.contains("[类型]")),
                    "At least one embedding input should contain injected parent context"
            );
            assertTrue(
                    embeddingInputs.stream().anyMatch(input -> !storedContents.contains(input)),
                    "At least one embedding input should differ from stored displayText content"
            );

            // Verify displayText/embeddingText separation
            ObjectMapper mapper = new ObjectMapper();
            for (DocumentChunk chunk : chunks) {
                // Parse metadata
                Map<String, Object> metadata = mapper.readValue(chunk.getMetadata(), Map.class);

                // Verify required metadata fields
                assertTrue(metadata.containsKey("chunkVersion"), "Should have chunkVersion");
                assertTrue(metadata.containsKey("documentTitle"), "Should have documentTitle");
                assertTrue(metadata.containsKey("sourceType"), "Should have sourceType");

                // Verify sourceType is MARKDOWN
                assertEquals("MARKDOWN", metadata.get("sourceType"));
            }

            // Cleanup
            Files.deleteIfExists(tempFile);

            // Reset for other tests
            preprocessingProperties.getChunking().setStructureAwareEnabled(false);
        }

        /**
         * Tests that re-ingestion refreshes chunk content and does not leave stale rows behind.
         */
        @Test
        void structureAwareReingestion_ReplacesChunkContentAndRemovesStaleRows() throws Exception {
            // Enable structure-aware mode for this test
            preprocessingProperties.getChunking().setStructureAwareEnabled(true);
            // Given: Create initial chunks
            String markdownContent = "# Test\n\nInitial content.";
            Path tempFile = Files.createTempFile("kb-reingest-", ".md");
            Files.writeString(tempFile, markdownContent);

            TestFixture fixture = createTestFixtureForStructureAware("reingest-owner", "reingest@example.com", tempFile);

            when(knowledgeBaseStorageService.getFilePath(fixture.document().getFileUrl())).thenReturn(tempFile);
            when(documentEmbeddingService.embedDocumentWithUsage(any(), anyString(), anyString()))
                    .thenAnswer(invocation -> embeddingExecution(invocation.getArgument(1, String.class), invocation.getArgument(2, String.class)));

            // First ingestion
            publisherService.publishPendingOutboxMessages();
            kbDocumentConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

            List<DocumentChunk> initialChunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(fixture.document().getId());
            int initialChunkCount = initialChunks.size();
            assertTrue(initialChunkCount > 0, "Should have initial chunks");

            // Update content and re-ingest
            String updatedContent = "# Updated Test\n\nUpdated content with more text to ensure different chunking.";
            Files.writeString(tempFile, updatedContent);

            // Create new outbox for re-ingestion
            kbDocumentOutboxRepository.save(KbDocumentOutbox.builder()
                    .kbId(fixture.knowledgeBase().getId())
                    .documentId(fixture.document().getId())
                    .status(OutboxStatus.NEW)
                    .retryCount(0)
                    .build());

            // Reset document status using JDBC
            jdbcTemplate.update(
                    "UPDATE kb_documents SET status = ? WHERE id = ?",
                    KbDocumentStatus.PENDING.name(),
                    fixture.document().getId()
            );

            // Second ingestion
            publisherService.publishPendingOutboxMessages();
            kbDocumentConsumer.onMessage(readLastMessage(), mock(com.rabbitmq.client.Channel.class), 1L);

            // Then: Verify chunk content refreshed and no stale rows remain
            List<DocumentChunk> newChunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(fixture.document().getId());
            assertTrue(newChunks.size() > 0, "Should have new chunks");
            assertEquals(newChunks.size(), documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(fixture.document().getId()).size());
            assertTrue(
                    newChunks.stream().anyMatch(chunk -> chunk.getContent().contains("Updated content")),
                    "At least one chunk should reflect the updated body content"
            );
            assertTrue(newChunks.size() <= initialChunkCount, "Re-ingestion should not leave stale rows behind");

            // Cleanup
            Files.deleteIfExists(tempFile);

            // Reset for other tests
            preprocessingProperties.getChunking().setStructureAwareEnabled(false);
        }

        private TestFixture createTestFixtureForStructureAware(String username, String email, Path filePath) {
            User owner = userRepository.save(User.builder()
                    .username(username)
                    .email(email)
                    .password("hashed")
                    .build());
            grantCredits(owner.getId());

            KnowledgeBase knowledgeBase = knowledgeBaseRepository.save(KnowledgeBase.builder()
                    .userId(owner.getId())
                    .name("Structure Aware KB")
                    .status(KnowledgeBaseStatus.ACTIVE)
                    .build());

            String fileName = filePath.getFileName().toString();
            KbDocument document = kbDocumentRepository.save(KbDocument.builder()
                    .kbId(knowledgeBase.getId())
                    .fileName(fileName)
                    .fileType("text/markdown")
                    .fileUrl("mock://" + fileName)
                    .status(KbDocumentStatus.PENDING)
                    .build());

            KbDocumentOutbox outbox = kbDocumentOutboxRepository.save(KbDocumentOutbox.builder()
                    .kbId(knowledgeBase.getId())
                    .documentId(document.getId())
                    .status(OutboxStatus.NEW)
                    .retryCount(0)
                    .build());

            return new TestFixture(owner, knowledgeBase, document, outbox);
        }
    }
}
