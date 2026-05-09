package com.josh.interviewj.kb;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatMessage;
import com.josh.interviewj.chat.model.ChatMessageType;
import com.josh.interviewj.chat.model.ChatRole;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.repository.ChatMessageRepository;
import com.josh.interviewj.chat.repository.ChatSessionRepository;
import com.josh.interviewj.chat.service.ChatSessionQueryService;
import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.knowledgebase.dto.request.KnowledgeBaseUpdateRequest;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentArtifact;
import com.josh.interviewj.knowledgebase.model.KbDocumentArtifactType;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KbFileCleanupTaskStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseIndexingStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentArtifactRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KbFileCleanupTaskRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseLifecycleService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseReindexService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseStatsService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseStorageService;
import com.josh.interviewj.knowledgebase.service.KbFileCleanupService;
import com.josh.interviewj.llm.LLMService;
import com.josh.interviewj.llm.support.LlmConfigCacheService;
import com.josh.interviewj.llm.support.LlmProviderSecretCryptoService;
import com.josh.interviewj.ragqa.dto.request.KnowledgeBaseQueryAskRequest;
import com.josh.interviewj.ragqa.service.KnowledgeBaseQueryService;
import com.josh.interviewj.ragqa.service.QueryEmbeddingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the end-to-end Phase 2 lifecycle, stats, delete, and reindex flows.
 */
@SpringBootTest
class KnowledgeBasePhase2IntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private KbDocumentRepository kbDocumentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private KbDocumentArtifactRepository kbDocumentArtifactRepository;

    @Autowired
    private KbDocumentOutboxRepository kbDocumentOutboxRepository;

    @Autowired
    private KbFileCleanupTaskRepository kbFileCleanupTaskRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private KnowledgeBaseLifecycleService knowledgeBaseLifecycleService;

    @Autowired
    private KnowledgeBaseStatsService knowledgeBaseStatsService;

    @Autowired
    private KnowledgeBaseReindexService knowledgeBaseReindexService;

    @Autowired
    private ChatSessionQueryService chatSessionQueryService;

    @Autowired
    private KnowledgeBaseQueryService knowledgeBaseQueryService;

    @Autowired
    private KnowledgeBaseStorageService knowledgeBaseStorageService;

    @Autowired
    private KbFileCleanupService kbFileCleanupService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LlmProviderSecretCryptoService cryptoService;

    @Autowired
    private LlmConfigCacheService llmConfigCacheService;

    @MockitoBean
    private QueryEmbeddingService queryEmbeddingService;

    @MockitoBean
    private LLMService llmService;

    private User owner;
    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        chatMessageRepository.deleteAllInBatch();
        chatSessionRepository.deleteAllInBatch();
        kbFileCleanupTaskRepository.deleteAllInBatch();
        kbDocumentArtifactRepository.deleteAllInBatch();
        kbDocumentOutboxRepository.deleteAllInBatch();
        documentChunkRepository.deleteAllInBatch();
        kbDocumentRepository.deleteAllInBatch();
        knowledgeBaseRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        owner = userRepository.save(User.builder()
                .username("phase2-owner")
                .email("phase2-owner@example.com")
                .password("hashed")
                .build());

        knowledgeBase = knowledgeBaseRepository.save(KnowledgeBase.builder()
                .userId(owner.getId())
                .name("Phase 2 KB")
                .description("Before update")
                .status(KnowledgeBaseStatus.ACTIVE)
                .build());

        Long providerId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_provider (
                    provider_key, display_name, base_url, enabled,
                    default_timeout_ms, default_max_retries, supported_usage_families, metadata
                ) VALUES ('kb-embedding-provider', 'KB Embedding Provider', 'https://provider.example.com/v1',
                          TRUE, 30000, 3, CAST('["EMBEDDING"]' AS jsonb), CAST('{}' AS jsonb))
                ON CONFLICT (provider_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    base_url = EXCLUDED.base_url,
                    enabled = EXCLUDED.enabled,
                    default_timeout_ms = EXCLUDED.default_timeout_ms,
                    default_max_retries = EXCLUDED.default_max_retries,
                    supported_usage_families = EXCLUDED.supported_usage_families,
                    metadata = EXCLUDED.metadata,
                    deleted_at = NULL
                RETURNING id
                """, Long.class);
        var encrypted = cryptoService.encrypt("kb-embedding-secret");
        jdbcTemplate.update("""
                INSERT INTO llm_provider_secret (
                    provider_id, api_key_ciphertext, api_key_masked, encryption_key_version, encryption_type
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (provider_id) DO UPDATE SET
                    api_key_ciphertext = EXCLUDED.api_key_ciphertext,
                    api_key_masked = EXCLUDED.api_key_masked,
                    encryption_key_version = EXCLUDED.encryption_key_version,
                    encryption_type = EXCLUDED.encryption_type,
                    updated_at = CURRENT_TIMESTAMP
                """,
                providerId,
                encrypted.ciphertext(),
                "kb-e****et",
                encrypted.keyVersion(),
                encrypted.encryptionType());
        Long modelId = jdbcTemplate.queryForObject("""
                INSERT INTO llm_model_catalog (
                    provider, provider_id, model_code, usage_family, display_name, active, metadata
                ) VALUES ('kb-embedding-provider', ?, 'kb-embedding-model', 'EMBEDDING', 'kb-embedding-model',
                          TRUE, CAST('{"dimension":2048}' AS jsonb))
                ON CONFLICT (provider, model_code, usage_family) DO UPDATE SET
                    provider_id = EXCLUDED.provider_id,
                    display_name = EXCLUDED.display_name,
                    active = EXCLUDED.active,
                    metadata = EXCLUDED.metadata,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """, Long.class, providerId);
        jdbcTemplate.update("""
                INSERT INTO llm_routing_policy (purpose, model_id, enabled, timeout_ms, max_retries, metadata)
                VALUES ('kb_document_embedding', ?, TRUE, 30000, 3, CAST('{}' AS jsonb))
                ON CONFLICT (purpose) DO UPDATE SET
                    model_id = EXCLUDED.model_id,
                    enabled = EXCLUDED.enabled,
                    timeout_ms = EXCLUDED.timeout_ms,
                    max_retries = EXCLUDED.max_retries,
                    metadata = EXCLUDED.metadata,
                    updated_at = CURRENT_TIMESTAMP
                """,
                modelId);
        llmConfigCacheService.invalidate();
    }

    @Test
    void updateAndStats_UseLiveAggregations() {
        KbDocument document = kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName("stats.pdf")
                .fileType("application/pdf")
                .fileUrl("stats.pdf")
                .fileSize(2048L)
                .status(KbDocumentStatus.FAILED)
                .build());
        upsertChunkWithDefaultSparseMaterialization(
                documentChunkRepository,
                document.getId(),
                knowledgeBase.getId(),
                "chunk-content",
                0,
                0,
                12,
                12,
                "{}"
        );
        ChatSession session = chatSessionRepository.save(ChatSession.builder()
                .userId(owner.getId())
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(knowledgeBase.getExternalId())
                .build());
        chatMessageRepository.save(ChatMessage.builder()
                .chatSessionId(session.getId())
                .role(ChatRole.ASSISTANT)
                .messageType(ChatMessageType.ANSWER)
                .content("answer")
                .metadata("{\"confidence\":0.8}")
                .sequenceNumber(1)
                .build());

        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName("Phase 2 KB Updated");
        var updated = knowledgeBaseLifecycleService.updateKnowledgeBase(owner.getUsername(), knowledgeBase.getExternalId(), request);
        var stats = knowledgeBaseStatsService.getStats(owner.getUsername(), knowledgeBase.getExternalId());

        assertEquals("Phase 2 KB Updated", updated.getName());
        assertEquals(1, stats.getTotalDocuments());
        assertEquals(1, stats.getTotalChunks());
        assertEquals(2048L, stats.getTotalSize());
        assertEquals(1, stats.getFailedDocuments());
        assertEquals(1L, stats.getTotalQueries());
        assertEquals(0.8D, stats.getAverageConfidence());
    }

    @Test
    void deleteDocument_RemovesChildrenAndCompletesCleanupTask() throws Exception {
        String storageKey = "phase2-delete-document.txt";
        Files.createDirectories(knowledgeBaseStorageService.getFilePath(storageKey).getParent());
        Files.writeString(knowledgeBaseStorageService.getFilePath(storageKey), "cleanup me");

        KbDocument document = kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName("delete.txt")
                .fileType("text/plain")
                .fileUrl(storageKey)
                .status(KbDocumentStatus.COMPLETED)
                .build());
        upsertChunkWithDefaultSparseMaterialization(
                documentChunkRepository,
                document.getId(),
                knowledgeBase.getId(),
                "chunk-content",
                0,
                0,
                12,
                12,
                "{}"
        );
        kbDocumentArtifactRepository.save(KbDocumentArtifact.builder()
                .documentId(document.getId())
                .artifactType(KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT)
                .content("artifact")
                .metadata("{}")
                .build());
        kbDocumentOutboxRepository.save(KbDocumentOutbox.builder()
                .kbId(knowledgeBase.getId())
                .documentId(document.getId())
                .status(OutboxStatus.NEW)
                .retryCount(0)
                .build());

        knowledgeBaseLifecycleService.deleteDocument(owner.getUsername(), knowledgeBase.getExternalId(), document.getExternalId());

        kbFileCleanupService.drainReadyTasks(PageRequest.of(0, 50));
        entityManager.clear();

        assertFalse(kbDocumentRepository.findById(document.getId()).isPresent());
        assertEquals(0L, documentChunkRepository.countByDocumentId(document.getId()));
        assertEquals(0L, kbDocumentOutboxRepository.countByDocumentId(document.getId()));
        List<com.josh.interviewj.knowledgebase.model.KbFileCleanupTask> tasks = kbFileCleanupTaskRepository.findAll();
        assertEquals(1, tasks.size());
        assertEquals(KbFileCleanupTaskStatus.COMPLETED, tasks.getFirst().getStatus());
        assertFalse(Files.exists(knowledgeBaseStorageService.getFilePath(storageKey)));
    }

    @Test
    void deleteKnowledgeBase_HidesDetailQueryAndSessions() {
        KbDocument document = kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName("delete-kb.txt")
                .fileType("text/plain")
                .fileUrl("delete-kb.txt")
                .status(KbDocumentStatus.FAILED)
                .build());
        chatSessionRepository.save(ChatSession.builder()
                .userId(owner.getId())
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(knowledgeBase.getExternalId())
                .build());

        knowledgeBaseLifecycleService.deleteKnowledgeBase(owner.getUsername(), knowledgeBase.getExternalId());

        KnowledgeBase persisted = knowledgeBaseRepository.findById(knowledgeBase.getId()).orElseThrow();
        assertEquals(KnowledgeBaseStatus.DELETED, persisted.getStatus());
        assertEquals(0L, kbDocumentRepository.countByKbId(knowledgeBase.getId()));
        BusinessException sessionException = assertThrows(BusinessException.class,
                () -> chatSessionQueryService.listKnowledgeBaseSessions(owner.getUsername(), knowledgeBase.getExternalId(), 0, 20, null));
        assertEquals("KB_001", sessionException.getErrorCode());

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("still there?");
        BusinessException queryException = assertThrows(BusinessException.class,
                () -> knowledgeBaseQueryService.askQuestion(owner.getUsername(), knowledgeBase.getExternalId(), request));
        assertEquals("KB_001", queryException.getErrorCode());
        assertNotNull(document.getId());
    }

    @Test
    void reindex_EmptyKnowledgeBase_ClearsPersistentIndexingStatusImmediately() {
        var response = knowledgeBaseReindexService.reindex(owner.getUsername(), knowledgeBase.getExternalId());

        assertEquals(KnowledgeBaseIndexingStatus.REINDEXING, response.getIndexingStatus());
        KnowledgeBase persisted = knowledgeBaseRepository.findById(knowledgeBase.getId()).orElseThrow();
        assertNull(persisted.getIndexingStatus());
    }

    @Test
    void reindex_DocumentsResetToPendingAndFreshOutbox() {
        KbDocument document = kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName("reindex.txt")
                .fileType("text/plain")
                .fileUrl("reindex.txt")
                .status(KbDocumentStatus.COMPLETED)
                .chunkCount(3)
                .expectedChunkCount(3)
                .embeddedChunkCount(3)
                .processedAt(LocalDateTime.now())
                .sparseReadyVersion("HYBRID_SPARSE_V1")
                .sparseReadyAt(LocalDateTime.now())
                .errorMessage("old error")
                .build());
        upsertChunkWithDefaultSparseMaterialization(
                documentChunkRepository,
                document.getId(),
                knowledgeBase.getId(),
                "chunk-content",
                0,
                0,
                12,
                12,
                "{}"
        );
        kbDocumentArtifactRepository.save(KbDocumentArtifact.builder()
                .documentId(document.getId())
                .artifactType(KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT)
                .content("artifact")
                .metadata("{}")
                .build());
        kbDocumentOutboxRepository.save(KbDocumentOutbox.builder()
                .kbId(knowledgeBase.getId())
                .documentId(document.getId())
                .status(OutboxStatus.SENT)
                .retryCount(1)
                .build());

        var response = knowledgeBaseReindexService.reindex(owner.getUsername(), knowledgeBase.getExternalId());
        KbDocument refreshed = kbDocumentRepository.findById(document.getId()).orElseThrow();

        assertEquals("ACCEPTED", response.getStatus());
        assertEquals(KbDocumentStatus.PENDING, refreshed.getStatus());
        assertEquals(0, refreshed.getChunkCount());
        assertEquals(0, refreshed.getExpectedChunkCount());
        assertEquals(0, refreshed.getEmbeddedChunkCount());
        assertNull(refreshed.getProcessedAt());
        assertNull(refreshed.getSparseReadyVersion());
        assertEquals(0L, documentChunkRepository.countByDocumentId(document.getId()));
        assertEquals(0, kbDocumentArtifactRepository.findAll().size());
        assertEquals(1L, kbDocumentOutboxRepository.countByDocumentId(document.getId()));
    }
}
