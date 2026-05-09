package com.josh.interviewj.service;

import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkCandidate;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkDerivationContext;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkDocumentContext;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkCandidateFactory;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkPersistencePayload;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkSemanticContext;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.StructureAwareChunkingService;
import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentQualitySummary;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.parser.DocumentParserRegistry;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentPreprocessingException;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentPreprocessingPipeline;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInput;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInputAdapter;
import com.josh.interviewj.knowledgebase.preprocessing.sparse.ChunkSparseMaterialization;
import com.josh.interviewj.knowledgebase.preprocessing.sparse.ChunkSparseMaterializer;
import com.josh.interviewj.knowledgebase.preprocessing.review.NormalizedReviewTextRenderer;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.service.KbChunkingService;
import com.josh.interviewj.knowledgebase.service.KbDocumentIngestionService;
import com.josh.interviewj.knowledgebase.service.KbDocumentArtifactService;
import com.josh.interviewj.knowledgebase.service.DocumentEmbeddingService;
import com.josh.interviewj.knowledgebase.service.KbIngestionStageException;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseReindexCompletionService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseStorageService;
import com.josh.interviewj.knowledgebase.service.ChunkPersistenceAssembler;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.resume.service.DocumentParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KbDocumentIngestionServiceTest {

    @Mock
    private KbDocumentRepository kbDocumentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private KnowledgeBaseStorageService knowledgeBaseStorageService;

    @Mock
    private DocumentParserService documentParserService;

    @Mock
    private DocumentParserRegistry documentParserRegistry;

    @Mock
    private DocumentPreprocessingPipeline documentPreprocessingPipeline;

    @Mock
    private FixedSizeChunkingInputAdapter fixedSizeChunkingInputAdapter;

    @Mock
    private KbDocumentArtifactService kbDocumentArtifactService;

    @Mock
    private NormalizedReviewTextRenderer normalizedReviewTextRenderer;

    @Mock
    private KbChunkingService kbChunkingService;

    @Mock
    private DocumentEmbeddingService documentEmbeddingService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private StructureAwareChunkingService structureAwareChunkingService;

    @Mock
    private ChunkCandidateFactory chunkCandidateFactory;

    @Mock
    private ChunkPersistenceAssembler chunkPersistenceAssembler;

    @Mock
    private DocumentPreprocessingProperties preprocessingProperties;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ChunkSparseMaterializer chunkSparseMaterializer;

    @Mock
    private KnowledgeBaseReindexCompletionService knowledgeBaseReindexCompletionService;

    @Mock
    private AiOperationGateway aiOperationGateway;

    @InjectMocks
    private KbDocumentIngestionService kbDocumentIngestionService;

    private KbDocument document;

    @BeforeEach
    void setUp() {
        document = KbDocument.builder()
                .id(123L)
                .kbId(456L)
                .fileName("mock.pdf")
                .fileUrl("mock://doc.pdf")
                .fileType("application/pdf")
                .status(KbDocumentStatus.PROCESSING)
                .build();

        when(kbDocumentRepository.findById(123L)).thenReturn(Optional.of(document));
        when(knowledgeBaseStorageService.getFilePath("mock://doc.pdf")).thenReturn(Path.of("mock.pdf"));
        when(documentParserRegistry.supports("application/pdf", "mock.pdf")).thenReturn(false);

        // Configure preprocessing properties with structure-aware disabled (use fixed-size fallback)
        ChunkingProperties chunkingProperties = new ChunkingProperties();
        chunkingProperties.setStructureAwareEnabled(false);
        when(preprocessingProperties.getChunking()).thenReturn(chunkingProperties);

        ReflectionTestUtils.setField(kbDocumentIngestionService, "maxDocumentChars", 500000);
        ReflectionTestUtils.setField(kbDocumentIngestionService, "maxChunks", 800);
        ReflectionTestUtils.setField(kbDocumentIngestionService, "sparseReadyVersion", "HYBRID_SPARSE_V1");
        lenient().when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(123L)).thenReturn(List.of());
        lenient().when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of());
        lenient().when(aiOperationGateway.prepareOperation(any())).thenReturn(new com.josh.interviewj.llm.gateway.dto.BusinessOperationContext(
                "biz-1",
                88L,
                "KB_DOCUMENT",
                "doc-1",
                "kb_document_embedding",
                List.of("KB_INGESTION_CREDITS"),
                Map.of()
        ));
        lenient().doNothing().when(aiOperationGateway).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
        lenient().when(documentEmbeddingService.embedDocumentWithUsage(any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String content = invocation.getArgument(2, String.class);
                    EmbeddingResponse response = new EmbeddingResponse(new float[]{0.1F, 0.2F}, "embedding_provider", "text-embedding-v4");
                    return new DocumentEmbeddingService.EmbeddingExecutionResult(
                            response,
                            new com.josh.interviewj.llm.gateway.dto.AiInvocationContext(
                                    invocation.getArgument(1, String.class),
                                    "kb_document_embedding",
                                    com.josh.interviewj.usage.model.UsageFamily.EMBEDDING,
                                    "KB_INGESTION_CREDITS",
                                    false,
                                    Map.of("content", content)
                            ),
                            new com.josh.interviewj.llm.gateway.dto.AiInvocationResult(
                                    com.josh.interviewj.llm.gateway.dto.AiInvocationKind.EMBEDDING,
                                    response,
                                    "embedding_provider",
                                    "text-embedding-v4",
                                    null,
                                    Map.of()
                            )
                    );
                });
        lenient().when(knowledgeBaseRepository.findById(456L)).thenReturn(Optional.of(KnowledgeBase.builder()
                .id(456L)
                .externalId(UUID.randomUUID())
                .userId(88L)
                .name("KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .build()));
        lenient().when(chunkSparseMaterializer.materialize(anyString(), anyString())).thenAnswer(invocation ->
                ChunkSparseMaterialization.builder()
                        .sparseContentText(invocation.getArgument(0, String.class))
                        .sparseEntitiesText(invocation.getArgument(0, String.class))
                        .sparseExactTerms(List.of())
                        .metadataJson(invocation.getArgument(1, String.class))
                        .build()
        );
        lenient().when(chunkCandidateFactory.createFixedSizeCandidate(any(), any(), any())).thenAnswer(invocation -> {
            KbChunkingService.ChunkPart chunk = invocation.getArgument(0);
            ChunkDocumentContext documentContext = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            List<FixedSizeChunkingInput.RetainedSegment> retainedSegments = invocation.getArgument(2);
            return ChunkCandidate.builder()
                    .chunkIndex(chunk.chunkIndex())
                    .blockOrders(retainedSegments.stream()
                            .map(FixedSizeChunkingInput.RetainedSegment::blockOrder)
                            .distinct()
                            .sorted()
                            .toList())
                    .bodyText(chunk.content())
                    .displayText(chunk.content())
                    .embeddingText(chunk.content())
                    .tokenCountEstimate(chunk.tokenCount())
                    .documentContext(documentContext)
                    .semanticContext(ChunkSemanticContext.builder()
                            .blockTypes(retainedSegments.stream()
                                    .map(FixedSizeChunkingInput.RetainedSegment::blockType)
                                    .filter(java.util.Objects::nonNull)
                                    .map(Enum::name)
                                    .distinct()
                                    .toList())
                            .pageNumbers(retainedSegments.stream()
                                    .map(FixedSizeChunkingInput.RetainedSegment::pageNumber)
                                    .filter(java.util.Objects::nonNull)
                                    .distinct()
                                    .sorted()
                                    .toList())
                            .sectionPath(List.of())
                            .build())
                    .derivationContext(ChunkDerivationContext.builder()
                            .startPosition(chunk.startPosition())
                            .endPosition(chunk.endPosition())
                            .retainedSegments(retainedSegments)
                            .build())
                    .build();
        });
        lenient().when(chunkPersistenceAssembler.assemble(any())).thenAnswer(invocation -> {
            ChunkCandidate candidate = invocation.getArgument(0);
            String displayText = candidate == null ? "" : candidate.displayText();
            return ChunkPersistencePayload.builder()
                    .metadataJson("{}")
                    .sparseContentText(displayText)
                    .sparseEntitiesText(displayText)
                    .sparseExactTerms(List.of())
                    .build();
        });
    }

    @Test
    void ingest_TextAtFiveHundredThousandChars_AllowsProcessing() {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenReturn("a".repeat(500000));
        when(kbChunkingService.chunk("a".repeat(500000))).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "chunk-0", 0, 1200, 1200)
        ));
        lenient().when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of());
        when(documentEmbeddingService.embedDocument("chunk-0")).thenReturn(new float[]{0.1F, 0.2F});
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1);

        assertDoesNotThrow(() -> kbDocumentIngestionService.ingest(123L));

        verify(kbDocumentRepository).updateExpectedChunkCount(123L, 1);
        verify(documentEmbeddingService).embedDocumentWithUsage(any(), anyString(), eq("chunk-0"));
        verify(kbDocumentRepository).updateEmbeddingProgress(123L, 1, KbDocumentStatus.PROCESSING);
    }

    @Test
    void ingest_TextLongerThanFiveHundredThousandChars_ThrowsTerminalException() {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenReturn("a".repeat(500001));

        KbDocumentIngestionService.TerminalIngestionException exception = assertThrows(
                KbDocumentIngestionService.TerminalIngestionException.class,
                () -> kbDocumentIngestionService.ingest(123L)
        );

        assertEquals("文档过大，请拆分后重新上传", exception.getMessage());
        verify(kbDocumentRepository, never()).updateExpectedChunkCount(anyLong(), anyInt());
        verify(documentChunkRepository, never()).upsertChunk(anyLong(), anyLong(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void ingest_ExpectedChunkCountAtSevenHundredNinetyNine_AllowsProcessing() {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenReturn("ok");
        when(kbChunkingService.chunk("ok")).thenReturn(createChunks(799));
        lenient().when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of());
        when(documentEmbeddingService.embedDocument(anyString())).thenReturn(new float[]{0.1F, 0.2F});
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1);

        assertDoesNotThrow(() -> kbDocumentIngestionService.ingest(123L));

        verify(kbDocumentRepository).updateExpectedChunkCount(123L, 799);
        verify(documentChunkRepository, times(799)).upsertChunk(eq(123L), eq(456L), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString());
        verify(documentEmbeddingService, times(799)).embedDocumentWithUsage(any(), anyString(), anyString());
    }

    @Test
    void ingest_ExpectedChunkCountAtEightHundred_AllowsProcessing() {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenReturn("ok");
        when(kbChunkingService.chunk("ok")).thenReturn(createChunks(800));
        lenient().when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of());
        when(documentEmbeddingService.embedDocument(anyString())).thenReturn(new float[]{0.1F, 0.2F});
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1);

        assertDoesNotThrow(() -> kbDocumentIngestionService.ingest(123L));

        verify(kbDocumentRepository).updateExpectedChunkCount(123L, 800);
        verify(documentChunkRepository, times(800)).upsertChunk(eq(123L), eq(456L), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString());
        verify(documentEmbeddingService, times(800)).embedDocumentWithUsage(any(), anyString(), anyString());
    }

    @Test
    void ingest_ExpectedChunkCountAboveEightHundred_ThrowsTerminalException() {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenReturn("ok");
        when(kbChunkingService.chunk("ok")).thenReturn(createChunks(801));

        KbDocumentIngestionService.TerminalIngestionException exception = assertThrows(
                KbDocumentIngestionService.TerminalIngestionException.class,
                () -> kbDocumentIngestionService.ingest(123L)
        );

        assertEquals("文档分块数量超过限制，请拆分后重新上传", exception.getMessage());
        verify(kbDocumentRepository, never()).updateExpectedChunkCount(anyLong(), anyInt());
        verify(documentEmbeddingService, never()).embedDocument(anyString());
    }

    @Test
    void ingest_RecordsUsageForSuccessfulDocumentEmbedding() {
        document.setExternalId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf")).thenReturn("ok");
        when(kbChunkingService.chunk("ok")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "chunk-0", 0, 6, 2)
        ));
        when(documentEmbeddingService.embedDocumentWithUsage(any(), anyString(), eq("chunk-0")))
                .thenReturn(new DocumentEmbeddingService.EmbeddingExecutionResult(
                        new EmbeddingResponse(new float[]{0.1F, 0.2F}, "embedding_provider", "text-embedding-v4"),
                        new com.josh.interviewj.llm.gateway.dto.AiInvocationContext(
                                "inv-1",
                                "kb_document_embedding",
                                com.josh.interviewj.usage.model.UsageFamily.EMBEDDING,
                                "KB_INGESTION_CREDITS",
                                false,
                                Map.of()
                        ),
                        new com.josh.interviewj.llm.gateway.dto.AiInvocationResult(
                                com.josh.interviewj.llm.gateway.dto.AiInvocationKind.EMBEDDING,
                                new EmbeddingResponse(new float[]{0.1F, 0.2F}, "embedding_provider", "text-embedding-v4"),
                                "embedding_provider",
                                "text-embedding-v4",
                                null,
                                Map.of()
                        )
                ));
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1);

        assertDoesNotThrow(() -> kbDocumentIngestionService.ingest(123L));

        verify(aiOperationGateway).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ingest_RepeatedAttemptsUseDifferentBusinessOperationAndInvocationIds() {
        document.setExternalId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        when(aiOperationGateway.prepareOperation(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf")).thenReturn("ok");
        when(kbChunkingService.chunk("ok")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "chunk-0", 0, 6, 2)
        ));
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1);

        kbDocumentIngestionService.ingest(123L);
        kbDocumentIngestionService.ingest(123L);

        var operationCaptor = org.mockito.ArgumentCaptor.forClass(com.josh.interviewj.llm.gateway.dto.BusinessOperationContext.class);
        verify(aiOperationGateway, org.mockito.Mockito.times(2)).prepareOperation(operationCaptor.capture());
        assertNotEquals(
                operationCaptor.getAllValues().get(0).businessOperationId(),
                operationCaptor.getAllValues().get(1).businessOperationId()
        );

        var invocationIdCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(documentEmbeddingService, org.mockito.Mockito.times(2))
                .embedDocumentWithUsage(any(), invocationIdCaptor.capture(), eq("chunk-0"));
        assertNotEquals(invocationIdCaptor.getAllValues().get(0), invocationIdCaptor.getAllValues().get(1));
    }

    @Test
    void ingestFixedSize_ReusesExistingEmbeddedChunkWhenChunkShapeMatches() {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenReturn("ok");
        when(kbChunkingService.chunk("ok")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "chunk-0", 0, 10, 10),
                new KbChunkingService.ChunkPart(1, "chunk-1", 10, 20, 10)
        ));
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(123L)).thenReturn(List.of(
                com.josh.interviewj.knowledgebase.model.DocumentChunk.builder()
                        .documentId(123L)
                        .kbId(456L)
                        .chunkIndex(0)
                        .content("chunk-0")
                        .startPosition(0)
                        .endPosition(10)
                        .tokenCount(10)
                        .metadata("{}")
                        .build()
        ));
        when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of(0));
        when(documentEmbeddingService.embedDocument("chunk-1")).thenReturn(new float[]{0.3F, 0.4F});
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1, 2);

        kbDocumentIngestionService.ingest(123L);

        var inOrder = inOrder(kbDocumentRepository, documentChunkRepository, documentEmbeddingService);
        inOrder.verify(kbDocumentRepository).updateExpectedChunkCount(123L, 2);
        inOrder.verify(kbDocumentRepository).clearSparseReady(123L);
        inOrder.verify(documentChunkRepository).upsertChunk(123L, 456L, "chunk-0", 0, 0, 10, 10, "{}", "chunk-0", "chunk-0", "");
        inOrder.verify(documentChunkRepository).upsertChunk(123L, 456L, "chunk-1", 1, 10, 20, 10, "{}", "chunk-1", "chunk-1", "");
        verify(documentChunkRepository).findEmbeddedChunkIndices(123L);
        verify(documentChunkRepository, never()).deleteByDocumentId(123L);
        verify(documentEmbeddingService, never()).embedDocumentWithUsage(any(), anyString(), eq("chunk-0"));
        verify(documentEmbeddingService).embedDocumentWithUsage(any(), anyString(), eq("chunk-1"));
        verify(kbDocumentRepository).updateEmbeddingProgress(123L, 1, KbDocumentStatus.PROCESSING);
        verify(kbDocumentRepository).updateEmbeddingProgress(123L, 2, KbDocumentStatus.PROCESSING);
        verify(kbDocumentRepository).markSparseReady(eq(123L), anyString(), any());
    }

    @Test
    void ingest_RebuildsDocumentAndEmbedsAllChunksFromScratch() {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenReturn("ok");
        when(kbChunkingService.chunk("ok")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "chunk-0", 0, 10, 10),
                new KbChunkingService.ChunkPart(1, "chunk-1", 10, 20, 10),
                new KbChunkingService.ChunkPart(2, "chunk-2", 20, 30, 10)
        ));
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(123L)).thenReturn(List.of());
        when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of());
        when(documentEmbeddingService.embedDocument("chunk-0")).thenReturn(new float[]{0.5F, 0.6F});
        when(documentEmbeddingService.embedDocument("chunk-1")).thenReturn(new float[]{0.1F, 0.2F});
        when(documentEmbeddingService.embedDocument("chunk-2")).thenReturn(new float[]{0.3F, 0.4F});
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1, 2, 3);

        kbDocumentIngestionService.ingest(123L);

        verify(kbDocumentRepository).updateExpectedChunkCount(123L, 3);
        verify(documentChunkRepository, times(3)).upsertChunk(eq(123L), eq(456L), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString());
        verify(documentChunkRepository).findEmbeddedChunkIndices(123L);
        verify(documentChunkRepository, never()).deleteByDocumentId(123L);
        verify(kbDocumentRepository).clearSparseReady(123L);
        verify(documentEmbeddingService).embedDocumentWithUsage(any(), anyString(), eq("chunk-0"));
        verify(documentEmbeddingService).embedDocumentWithUsage(any(), anyString(), eq("chunk-1"));
        verify(documentEmbeddingService).embedDocumentWithUsage(any(), anyString(), eq("chunk-2"));
        verify(documentChunkRepository, times(3)).updateEmbeddingIfNull(eq(123L), anyInt(), anyString());
        verify(kbDocumentRepository).updateEmbeddingProgress(123L, 1, KbDocumentStatus.PROCESSING);
        verify(kbDocumentRepository).updateEmbeddingProgress(123L, 2, KbDocumentStatus.PROCESSING);
        verify(kbDocumentRepository).updateEmbeddingProgress(123L, 3, KbDocumentStatus.PROCESSING);
        verify(kbDocumentRepository).markSparseReady(eq(123L), eq("HYBRID_SPARSE_V1"), any());
    }

    @Test
    void ingest_WhenEmbeddingContextHasNoUserId_FailsInsteadOfUsingContextlessEmbeddingFallback() {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenReturn("ok");
        when(kbChunkingService.chunk("ok")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "chunk-0", 0, 10, 10)
        ));
        when(knowledgeBaseRepository.findById(456L)).thenReturn(Optional.of(
                KnowledgeBase.builder()
                        .id(456L)
                        .externalId(UUID.randomUUID())
                        .userId(null)
                        .name("KB")
                        .status(KnowledgeBaseStatus.ACTIVE)
                        .build()
        ));
        when(documentEmbeddingService.embedDocumentWithUsage("chunk-0"))
                .thenReturn(new EmbeddingResponse(new float[]{0.1F, 0.2F}, "embedding_provider", "text-embedding-v4"));

        KbIngestionStageException exception = assertThrows(
                KbIngestionStageException.class,
                () -> kbDocumentIngestionService.ingest(123L)
        );

        assertEquals("EMBED", exception.getStage());
        assertEquals("知识库处理服务暂时不可用，请稍后重试", exception.getMessage());
        verify(documentEmbeddingService, never()).embedDocumentWithUsage(any(), anyString(), eq("chunk-0"));
        verify(documentEmbeddingService, never()).embedDocumentWithUsage("chunk-0");
    }

    @Test
    void ingest_ParserFailure_ThrowsTerminalExceptionWithKnowledgeBaseMessage() {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenThrow(new BusinessException(ErrorCode.RESUME_003, "Resume parse failed"));

        KbDocumentIngestionService.TerminalIngestionException exception = assertThrows(
                KbDocumentIngestionService.TerminalIngestionException.class,
                () -> kbDocumentIngestionService.ingest(123L)
        );

        assertEquals("文档解析失败，请确认文件可正常打开且内容可读取", exception.getMessage());
    }

    @Test
    void ingest_SupportedStructuredDocument_UsesPreprocessingAndWritesChunkMetadata() throws Exception {
        when(documentParserRegistry.supports("application/pdf", "mock.pdf")).thenReturn(true);
        NormalizedDocument normalizedDocument = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("mock.pdf")
                .metadata(java.util.Map.of("preprocessingVersion", "v1"))
                .qualitySummary(DocumentQualitySummary.builder().fitForMainIndex(true).build())
                .build();
        FixedSizeChunkingInput fixedSizeChunkingInput = FixedSizeChunkingInput.builder()
                .normalizedTextForChunking("chunk-0")
                .retainedSegments(List.of(
                        FixedSizeChunkingInput.RetainedSegment.builder()
                                .blockOrder(7)
                                .startOffset(0)
                                .endOffset(7)
                                .pageNumber(2)
                                .qualityFlags(List.of("HAS_LOW_SIGNAL_WARN"))
                                .preprocessingWarnings(List.of("POSSIBLE_MULTI_COLUMN_LAYOUT"))
                                .build()
                ))
                .build();
        when(documentPreprocessingPipeline.preprocess(Path.of("mock.pdf"), "application/pdf", "mock.pdf"))
                .thenReturn(normalizedDocument);
        when(normalizedReviewTextRenderer.render(normalizedDocument.reviewProjection())).thenReturn("[BLOCK]");
        when(fixedSizeChunkingInputAdapter.adapt(normalizedDocument)).thenReturn(fixedSizeChunkingInput);
        when(kbChunkingService.chunk("chunk-0")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "chunk-0", 0, 7, 7)
        ));
        lenient().when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of());
        when(documentEmbeddingService.embedDocument("chunk-0")).thenReturn(new float[]{0.1F, 0.2F});
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1);
        when(chunkPersistenceAssembler.assemble(any())).thenReturn(ChunkPersistencePayload.builder()
                .metadataJson("{\"preprocessingVersion\":\"v1\"}")
                .sparseContentText("chunk-0")
                .sparseEntitiesText("chunk-0")
                .sparseExactTerms(List.of())
                .build());

        kbDocumentIngestionService.ingest(123L);

        verify(documentPreprocessingPipeline).preprocess(Path.of("mock.pdf"), "application/pdf", "mock.pdf");
        verify(kbDocumentArtifactService).upsertNormalizedReviewText(eq(123L), eq("[BLOCK]"), any());
        verify(documentParserService, never()).extractText(any(), anyString());
        verify(documentChunkRepository).upsertChunk(123L, 456L, "chunk-0", 0, 0, 7, 7, "{\"preprocessingVersion\":\"v1\"}", "chunk-0", "chunk-0", "");
    }

    @Test
    void ingest_StructuredShortButReadableDocument_DoesNotFailOnQualityGate() throws Exception {
        when(documentParserRegistry.supports("application/pdf", "mock.pdf")).thenReturn(true);
        NormalizedDocument normalizedDocument = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("mock.pdf")
                .metadata(java.util.Map.of("preprocessingVersion", "v1"))
                .qualitySummary(DocumentQualitySummary.builder().fitForMainIndex(false).build())
                .build();
        when(documentPreprocessingPipeline.preprocess(Path.of("mock.pdf"), "application/pdf", "mock.pdf"))
                .thenReturn(normalizedDocument);
        when(normalizedReviewTextRenderer.render(normalizedDocument.reviewProjection())).thenReturn("[BLOCK]");
        when(fixedSizeChunkingInputAdapter.adapt(normalizedDocument)).thenReturn(FixedSizeChunkingInput.builder()
                .normalizedTextForChunking("normalized")
                .retainedSegments(List.of())
                .build());
        when(kbChunkingService.chunk("normalized")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "normalized", 0, 10, 10)
        ));
        lenient().when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of());
        when(documentEmbeddingService.embedDocument("normalized")).thenReturn(new float[]{0.1F, 0.2F});
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1);

        assertDoesNotThrow(() -> kbDocumentIngestionService.ingest(123L));

        verify(documentChunkRepository).upsertChunk(123L, 456L, "normalized", 0, 0, 10, 10, "{}", "normalized", "normalized", "");
    }

    @Test
    void ingest_AdapterEmptyOutput_ThrowsTerminalExceptionBeforeAnyChunkUpsert() {
        when(documentParserRegistry.supports("application/pdf", "mock.pdf")).thenReturn(true);
        NormalizedDocument normalizedDocument = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("mock.pdf")
                .metadata(java.util.Map.of("preprocessingVersion", "v1"))
                .qualitySummary(DocumentQualitySummary.builder().fitForMainIndex(true).build())
                .build();
        when(documentPreprocessingPipeline.preprocess(Path.of("mock.pdf"), "application/pdf", "mock.pdf"))
                .thenReturn(normalizedDocument);
        when(normalizedReviewTextRenderer.render(normalizedDocument.reviewProjection())).thenReturn("[BLOCK]");
        when(fixedSizeChunkingInputAdapter.adapt(normalizedDocument)).thenReturn(FixedSizeChunkingInput.builder()
                .normalizedTextForChunking("   ")
                .retainedSegments(List.of())
                .build());

        KbDocumentIngestionService.TerminalIngestionException exception = assertThrows(
                KbDocumentIngestionService.TerminalIngestionException.class,
                () -> kbDocumentIngestionService.ingest(123L)
        );

        assertEquals("文档预处理后无可用内容，请确认文件正文可读取", exception.getMessage());
        verify(documentChunkRepository, never()).upsertChunk(anyLong(), anyLong(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingest_StructuredDocument_WritesChunkLevelRetrievalTraceWithoutFiltering() throws Exception {
        when(documentParserRegistry.supports("application/pdf", "mock.pdf")).thenReturn(true);
        NormalizedDocument normalizedDocument = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("mock.pdf")
                .metadata(java.util.Map.of("preprocessingVersion", "v1"))
                .qualitySummary(DocumentQualitySummary.builder().fitForMainIndex(true).build())
                .build();
        FixedSizeChunkingInput fixedSizeChunkingInput = FixedSizeChunkingInput.builder()
                .normalizedTextForChunking("soft keep")
                .retainedSegments(List.of(
                        FixedSizeChunkingInput.RetainedSegment.builder()
                                .blockOrder(7)
                                .startOffset(0)
                                .endOffset(4)
                                .pageNumber(2)
                                .qualityFlags(List.of("HAS_SOFT_DEINDEX_BLOCK"))
                                .preprocessingWarnings(List.of("POSSIBLE_MULTI_COLUMN_LAYOUT"))
                                .retrievalDisposition(com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition.SOFT_DEINDEX)
                                .retrievalDispositionReasonCodes(List.of(
                                        com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDispositionReasonCode.APPENDIX_SAMPLE_PAYLOAD
                                ))
                                .retrievalDispositionEvidence(List.of("reason:APPENDIX_SAMPLE_PAYLOAD"))
                                .build(),
                        FixedSizeChunkingInput.RetainedSegment.builder()
                                .blockOrder(8)
                                .startOffset(5)
                                .endOffset(9)
                                .pageNumber(2)
                                .qualityFlags(List.of())
                                .preprocessingWarnings(List.of("POSSIBLE_MULTI_COLUMN_LAYOUT"))
                                .retrievalDisposition(com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition.KEEP)
                                .retrievalDispositionReasonCodes(List.of())
                                .retrievalDispositionEvidence(List.of())
                                .build()
                ))
                .build();
        when(documentPreprocessingPipeline.preprocess(Path.of("mock.pdf"), "application/pdf", "mock.pdf"))
                .thenReturn(normalizedDocument);
        when(normalizedReviewTextRenderer.render(normalizedDocument.reviewProjection())).thenReturn("[BLOCK]");
        when(fixedSizeChunkingInputAdapter.adapt(normalizedDocument)).thenReturn(fixedSizeChunkingInput);
        when(kbChunkingService.chunk("soft keep")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "soft keep", 0, 9, 9)
        ));
        lenient().when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of());
        when(documentEmbeddingService.embedDocument("soft keep")).thenReturn(new float[]{0.1F, 0.2F});
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1);
        when(chunkPersistenceAssembler.assemble(any())).thenAnswer(invocation -> {
            ChunkCandidate candidate = invocation.getArgument(0);
            assertEquals(List.of(7, 8), candidate.blockOrders());
            assertEquals(List.of(2), candidate.pageNumbers());
            assertEquals(2, candidate.derivationContext().retainedSegments().size());
            assertEquals(
                    com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition.SOFT_DEINDEX,
                    candidate.derivationContext().retainedSegments().get(0).retrievalDisposition()
            );
            assertEquals(
                    List.of(com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDispositionReasonCode.APPENDIX_SAMPLE_PAYLOAD),
                    candidate.derivationContext().retainedSegments().get(0).retrievalDispositionReasonCodes()
            );
            assertEquals(
                    com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition.KEEP,
                    candidate.derivationContext().retainedSegments().get(1).retrievalDisposition()
            );
            return ChunkPersistencePayload.builder()
                    .metadataJson("{\"metadata\":\"ok\"}")
                    .sparseContentText("soft keep")
                    .sparseEntitiesText("soft keep")
                    .sparseExactTerms(List.of())
                    .build();
        });

        kbDocumentIngestionService.ingest(123L);
        verify(documentChunkRepository).upsertChunk(123L, 456L, "soft keep", 0, 0, 9, 9, "{\"metadata\":\"ok\"}", "soft keep", "soft keep", "");
        verify(documentEmbeddingService).embedDocumentWithUsage(any(), anyString(), eq("soft keep"));
    }

    @Test
    void ingest_StructuredDocument_PersistsReviewArtifactBeforeChunking() {
        when(documentParserRegistry.supports("application/pdf", "mock.pdf")).thenReturn(true);
        NormalizedDocument normalizedDocument = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("mock.pdf")
                .metadata(Map.of("preprocessingVersion", "v1"))
                .qualitySummary(DocumentQualitySummary.builder().fitForMainIndex(true).build())
                .build();
        FixedSizeChunkingInput fixedSizeChunkingInput = FixedSizeChunkingInput.builder()
                .normalizedTextForChunking("index only")
                .retainedSegments(List.of())
                .build();
        when(documentPreprocessingPipeline.preprocess(Path.of("mock.pdf"), "application/pdf", "mock.pdf"))
                .thenReturn(normalizedDocument);
        when(normalizedReviewTextRenderer.render(normalizedDocument.reviewProjection())).thenReturn("[BLOCK]");
        when(fixedSizeChunkingInputAdapter.adapt(normalizedDocument)).thenReturn(fixedSizeChunkingInput);
        when(kbChunkingService.chunk("index only")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "index only", 0, 10, 10)
        ));
        lenient().when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of());
        when(documentEmbeddingService.embedDocument("index only")).thenReturn(new float[]{0.1F, 0.2F});
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1);

        kbDocumentIngestionService.ingest(123L);

        var inOrder = inOrder(documentPreprocessingPipeline, normalizedReviewTextRenderer, kbDocumentArtifactService,
                fixedSizeChunkingInputAdapter, kbChunkingService);
        inOrder.verify(documentPreprocessingPipeline).preprocess(Path.of("mock.pdf"), "application/pdf", "mock.pdf");
        inOrder.verify(normalizedReviewTextRenderer).render(normalizedDocument.reviewProjection());
        inOrder.verify(kbDocumentArtifactService).upsertNormalizedReviewText(eq(123L), eq("[BLOCK]"), any());
        inOrder.verify(fixedSizeChunkingInputAdapter).adapt(normalizedDocument);
        inOrder.verify(kbChunkingService).chunk("index only");
    }

    @Test
    void ingest_StructuredPreprocessFailure_ThrowsTerminalExceptionBeforeAnyChunkUpsert() {
        when(documentParserRegistry.supports("application/pdf", "mock.pdf")).thenReturn(true);
        when(documentPreprocessingPipeline.preprocess(Path.of("mock.pdf"), "application/pdf", "mock.pdf"))
                .thenThrow(new DocumentPreprocessingException("broken structured parse"));

        KbDocumentIngestionService.TerminalIngestionException exception = assertThrows(
                KbDocumentIngestionService.TerminalIngestionException.class,
                () -> kbDocumentIngestionService.ingest(123L)
        );

        assertEquals("文档解析失败，请确认文件可正常打开且内容可读取", exception.getMessage());
        verify(fixedSizeChunkingInputAdapter, never()).adapt(any());
        verify(documentChunkRepository, never()).upsertChunk(anyLong(), anyLong(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void ingest_StructureAwareChunkingInternalBug_ThrowsNonBlankFailureMessage() {
        when(documentParserRegistry.supports("application/pdf", "mock.pdf")).thenReturn(true);
        ChunkingProperties chunkingProperties = new ChunkingProperties();
        chunkingProperties.setStructureAwareEnabled(true);
        when(preprocessingProperties.getChunking()).thenReturn(chunkingProperties);

        NormalizedDocument normalizedDocument = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("mock.pdf")
                .title(null)
                .metadata(Map.of("preprocessingVersion", "v1"))
                .qualitySummary(DocumentQualitySummary.builder().fitForMainIndex(true).build())
                .build();
        when(documentPreprocessingPipeline.preprocess(Path.of("mock.pdf"), "application/pdf", "mock.pdf"))
                .thenReturn(normalizedDocument);
        when(normalizedReviewTextRenderer.render(normalizedDocument.reviewProjection())).thenReturn("[BLOCK]");
        when(structureAwareChunkingService.chunk(normalizedDocument)).thenThrow(new NullPointerException());

        RuntimeException exception = assertThrows(RuntimeException.class, () -> kbDocumentIngestionService.ingest(123L));

        assertNotNull(exception.getMessage());
        assertFalse(exception.getMessage().isBlank(), "Failure message should not be blank");
        verify(documentChunkRepository, never()).upsertChunk(anyLong(), anyLong(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void ingest_RetryableEmbeddingFailure_PreservesChunksAndSecondAttemptCompletes() throws Exception {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenReturn("ok");
        when(kbChunkingService.chunk("ok")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "chunk-0", 0, 10, 10),
                new KbChunkingService.ChunkPart(1, "chunk-1", 10, 20, 10)
        ));
        when(documentEmbeddingService.embedDocumentWithUsage(any(), anyString(), eq("chunk-0")))
                .thenReturn(new DocumentEmbeddingService.EmbeddingExecutionResult(
                        new EmbeddingResponse(new float[]{0.1F, 0.2F}, "embedding_provider", "text-embedding-v4"),
                        new com.josh.interviewj.llm.gateway.dto.AiInvocationContext("inv-0", "kb_document_embedding", com.josh.interviewj.usage.model.UsageFamily.EMBEDDING, "KB_INGESTION_CREDITS", false, Map.of()),
                        new com.josh.interviewj.llm.gateway.dto.AiInvocationResult(
                                com.josh.interviewj.llm.gateway.dto.AiInvocationKind.EMBEDDING,
                                new EmbeddingResponse(new float[]{0.1F, 0.2F}, "embedding_provider", "text-embedding-v4"),
                                "embedding_provider",
                                "text-embedding-v4",
                                null,
                                Map.of()
                        )
                ));
        when(documentEmbeddingService.embedDocumentWithUsage(any(), anyString(), eq("chunk-1")))
                .thenThrow(new RuntimeException("知识库处理服务暂时不可用，请稍后重试", new BusinessException(ErrorCode.LLM_001, "down")))
                .thenReturn(new DocumentEmbeddingService.EmbeddingExecutionResult(
                        new EmbeddingResponse(new float[]{0.3F, 0.4F}, "embedding_provider", "text-embedding-v4"),
                        new com.josh.interviewj.llm.gateway.dto.AiInvocationContext("inv-1", "kb_document_embedding", com.josh.interviewj.usage.model.UsageFamily.EMBEDDING, "KB_INGESTION_CREDITS", false, Map.of()),
                        new com.josh.interviewj.llm.gateway.dto.AiInvocationResult(
                                com.josh.interviewj.llm.gateway.dto.AiInvocationKind.EMBEDDING,
                                new EmbeddingResponse(new float[]{0.3F, 0.4F}, "embedding_provider", "text-embedding-v4"),
                                "embedding_provider",
                                "text-embedding-v4",
                                null,
                                Map.of()
                        )
                ));
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1, 1, 2);

        RuntimeException firstAttemptException = assertThrows(RuntimeException.class, () -> kbDocumentIngestionService.ingest(123L));
        assertEquals("知识库处理服务暂时不可用，请稍后重试", firstAttemptException.getMessage());

        kbDocumentIngestionService.ingest(123L);

        verify(documentChunkRepository, times(4)).upsertChunk(eq(123L), eq(456L), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), eq("{}"), anyString(), anyString(), anyString());
        verify(documentEmbeddingService, times(2)).embedDocumentWithUsage(any(), anyString(), eq("chunk-0"));
        verify(documentEmbeddingService, times(2)).embedDocumentWithUsage(any(), anyString(), eq("chunk-1"));
    }

    @Test
    void ingestAndFinalize_Success_RecalculatesKnowledgeBaseTotalChunks() {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenReturn("ok");
        when(kbChunkingService.chunk("ok")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "chunk-0", 0, 10, 10),
                new KbChunkingService.ChunkPart(1, "chunk-1", 10, 20, 10)
        ));
        lenient().when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of());
        when(documentEmbeddingService.embedDocument("chunk-0")).thenReturn(new float[]{0.1F, 0.2F});
        when(documentEmbeddingService.embedDocument("chunk-1")).thenReturn(new float[]{0.3F, 0.4F});
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1, 2);
        when(kbDocumentRepository.markCompleted(eq(123L), eq(KbDocumentStatus.PROCESSING), eq(KbDocumentStatus.COMPLETED), any(java.time.LocalDateTime.class)))
                .thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        TransactionSynchronizationManager.initSynchronization();
        try {
            kbDocumentIngestionService.ingestAndFinalize(123L, 789L, 60000L);

            verify(kbDocumentRepository).markCompleted(eq(123L), eq(KbDocumentStatus.PROCESSING), eq(KbDocumentStatus.COMPLETED), any(java.time.LocalDateTime.class));
            verify(knowledgeBaseRepository).incrementTotalChunks(456L, 2);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());

            verify(valueOperations).set("kb:doc:processed:789", "1", 60000L, TimeUnit.MILLISECONDS);
            verify(knowledgeBaseReindexCompletionService).completeIfIdle(456L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void ingestAndFinalize_WhenCompletionFailsAfterCommit_DoesNotThrow() {
        when(documentParserService.extractText(Path.of("mock.pdf"), "application/pdf"))
                .thenReturn("ok");
        when(kbChunkingService.chunk("ok")).thenReturn(List.of(
                new KbChunkingService.ChunkPart(0, "chunk-0", 0, 10, 10),
                new KbChunkingService.ChunkPart(1, "chunk-1", 10, 20, 10)
        ));
        lenient().when(documentChunkRepository.findEmbeddedChunkIndices(123L)).thenReturn(List.of());
        when(documentEmbeddingService.embedDocument("chunk-0")).thenReturn(new float[]{0.1F, 0.2F});
        when(documentEmbeddingService.embedDocument("chunk-1")).thenReturn(new float[]{0.3F, 0.4F});
        when(documentChunkRepository.countEmbeddedChunks(123L)).thenReturn(1, 2);
        when(kbDocumentRepository.markCompleted(eq(123L), eq(KbDocumentStatus.PROCESSING), eq(KbDocumentStatus.COMPLETED), any(java.time.LocalDateTime.class)))
                .thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        doAnswer(invocation -> {
            throw new IllegalStateException("No active transaction");
        }).when(knowledgeBaseReindexCompletionService).completeIfIdle(456L);

        TransactionSynchronizationManager.initSynchronization();
        try {
            kbDocumentIngestionService.ingestAndFinalize(123L, 790L, 60000L);

            assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit()));

            verify(valueOperations).set("kb:doc:processed:790", "1", 60000L, TimeUnit.MILLISECONDS);
            verify(knowledgeBaseReindexCompletionService).completeIfIdle(456L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private List<KbChunkingService.ChunkPart> createChunks(int count) {
        List<KbChunkingService.ChunkPart> chunks = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int start = index * 10;
            int end = start + 10;
            chunks.add(new KbChunkingService.ChunkPart(index, "chunk-" + index, start, end, 10));
        }
        return chunks;
    }
}
