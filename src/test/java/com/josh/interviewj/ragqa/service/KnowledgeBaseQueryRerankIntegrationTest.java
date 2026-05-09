package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.chat.config.ChatProperties;
import com.josh.interviewj.chat.dto.ChatContextWindow;
import com.josh.interviewj.chat.dto.ChatTurnWriteResult;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseAccessService;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.routing.DatabaseLlmRouteSnapshotService;
import com.josh.interviewj.ragqa.config.ContextAssemblyProperties;
import com.josh.interviewj.ragqa.config.ContextSelectionProperties;
import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.ragqa.dto.request.KnowledgeBaseQueryAskRequest;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseQueryResponse;
import com.josh.interviewj.ragqa.model.ContextAssemblyResult;
import com.josh.interviewj.ragqa.model.ContextBlock;
import com.josh.interviewj.ragqa.model.ContextBlockAssemblyResult;
import com.josh.interviewj.ragqa.model.DatabaseRerankConfig;
import com.josh.interviewj.ragqa.model.PreRerankCandidate;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RankedChunkCandidate;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievalProvenance;
import com.josh.interviewj.ragqa.repository.ChunkSearchRepository;
import com.josh.interviewj.ragqa.repository.ChunkSparseSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseQueryRerankIntegrationTest {

    private final KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
    private final QueryEmbeddingService queryEmbeddingService = mock(QueryEmbeddingService.class);
    private final ChunkSearchRepository chunkSearchRepository = mock(ChunkSearchRepository.class);
    private final ChunkSparseSearchRepository chunkSparseSearchRepository = mock(ChunkSparseSearchRepository.class);
    private final KbDocumentRepository kbDocumentRepository = mock(KbDocumentRepository.class);
    private final RagQaChatSessionService ragQaChatSessionService = mock(RagQaChatSessionService.class);
    private final PreRerankCandidateBuilder preRerankCandidateBuilder = mock(PreRerankCandidateBuilder.class);
    private final ChunkRerankService chunkRerankService = mock(ChunkRerankService.class);
    private final ContextBlockAssembler contextBlockAssembler = mock(ContextBlockAssembler.class);
    private final CoverageAwareContextSelector coverageAwareContextSelector = mock(CoverageAwareContextSelector.class);
    private final AiOperationGateway aiOperationGateway = mock(AiOperationGateway.class);

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final QueryUnderstandingProperties queryProperties = new QueryUnderstandingProperties();
    private final ChatProperties chatProperties = new ChatProperties();
    private final ContextAssemblyProperties contextAssemblyProperties = new ContextAssemblyProperties();
    private final ContextSelectionProperties contextSelectionProperties = new ContextSelectionProperties();
    private final AtomicReference<DatabaseRerankConfig> rerankConfigRef = new AtomicReference<>();
    private final AtomicReference<String> invalidRerankReasonRef = new AtomicReference<>();

    private KnowledgeBaseQueryService service;
    private KnowledgeBase knowledgeBase;
    private ChatSession chatSession;

    @BeforeEach
    void setUp() {
        queryProperties.setEnabled(false);
        QueryUnderstandingService queryUnderstandingService = new QueryUnderstandingService(
                queryProperties,
                new QueryNormalizationService(queryProperties),
                new QueryProfileDetector(),
                new QueryRewriteService(aiOperationGateway, objectMapper, queryProperties)
        );
        lenient().when(aiOperationGateway.prepareOperation(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(aiOperationGateway.executeInvocation(any(), any(), any())).thenAnswer(invocation -> {
            AiInvocationInput input = invocation.getArgument(2);
            return AiInvocationResult.fromChat(new LlmResponse(
                    "{\"rewrittenQuery\":\"" + input.userPrompt() + "\"}",
                    "mock-provider",
                    "mock-model",
                    new ProviderUsage(com.josh.interviewj.usage.model.UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
            ));
        });
        lenient().when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), anyString()))
                .thenAnswer(invocation -> embeddingExecution(
                        invocation.getArgument(1, String.class),
                        invocation.getArgument(2, String.class)
                ));
        DatabaseRerankConfigResolver databaseRerankConfigResolver = new DatabaseRerankConfigResolver(
                () -> new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        rerankConfigRef.get() == null ? Map.of() : Map.of("kb_query_rerank", rerankConfigRef.get()),
                        invalidRerankReasonRef.get() == null ? Map.of() : Map.of("kb_query_rerank", invalidRerankReasonRef.get())
                )
        );
        service = new KnowledgeBaseQueryService(
                knowledgeBaseAccessService,
                queryUnderstandingService,
                new RetrievalPlanBuilder(queryProperties),
                queryEmbeddingService,
                chunkSearchRepository,
                chunkSparseSearchRepository,
                kbDocumentRepository,
                objectMapper,
                new RetrievalResultFusionService(),
                Executors.newSingleThreadExecutor(),
                ragQaChatSessionService,
                chatProperties,
                preRerankCandidateBuilder,
                chunkRerankService,
                contextBlockAssembler,
                coverageAwareContextSelector,
                databaseRerankConfigResolver,
                contextAssemblyProperties,
                contextSelectionProperties,
                aiOperationGateway
        );
        ReflectionTestUtils.setField(service, "defaultTopK", 5);
        ReflectionTestUtils.setField(service, "maxTopK", 20);
        ReflectionTestUtils.setField(service, "sourceContentMaxChars", 500);
        ReflectionTestUtils.setField(service, "sparseEnabled", false);
        knowledgeBase = KnowledgeBase.builder()
                .id(10L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .name("KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        chatSession = ChatSession.builder()
                .id(20L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(knowledgeBase.getExternalId())
                .status(ChatSessionStatus.ACTIVE)
                .build();

        when(knowledgeBaseAccessService.requireQueryableKnowledgeBase("owner", knowledgeBase.getExternalId())).thenReturn(knowledgeBase);
        when(ragQaChatSessionService.resolveContext(eq("owner"), eq(knowledgeBase), any()))
                .thenReturn(new RagQaChatSessionService.RagQaChatSessionContext(
                        chatSession,
                        new ChatContextWindow(List.of(), false, 0, 0)
                ));
        when(ragQaChatSessionService.persistTurn(eq(chatSession), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(new ChatTurnWriteResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        lenient().when(queryEmbeddingService.embedQuery("Redis?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("chunk-content", 0, 0.91D)));
        rerankConfigRef.set(null);
        invalidRerankReasonRef.set(null);
    }

    @Test
    void askQuestion_RerankRouteMissing_UsesOldFusedChunkPath() {

        KnowledgeBaseQueryResponse response = service.askQuestion("owner", knowledgeBase.getExternalId(), request());

        assertThat(capturedUserPrompt(context -> "rag".equals(context.purpose())))
                .contains("[Document: Redis.pdf, Chunk: 0]");
        verify(preRerankCandidateBuilder, never()).build(any(), anyInt());
        assertThat(response.getRetrievedChunkCount()).isEqualTo(1);
        assertThat(response.getSources()).hasSize(1);
    }

    @Test
    void askQuestion_RerankEnabled_SuccessPath_UsesBlockPrompt() {
        rerankConfigRef.set(rerankConfig());
        contextAssemblyProperties.setEnabled(true);
        when(preRerankCandidateBuilder.build(any(), anyInt())).thenReturn(List.of(preCandidate()));
        when(chunkRerankService.rerank(any(), anyString(), any(), any(), any())).thenReturn(
                new ChunkRerankService.ChunkRerankResult(List.of(rankedSeed()), false, "none")
        );
        when(contextBlockAssembler.assemble(any())).thenReturn(
                new ContextBlockAssemblyResult(List.of(selectedBlock()), 0, 0, 0, false, "none")
        );
        when(coverageAwareContextSelector.select(any(), anyInt())).thenReturn(
                new ContextAssemblyResult(List.of(selectedBlock()), 60, 1, 1, 0, 0, "none")
        );

        KnowledgeBaseQueryResponse response = service.askQuestion("owner", knowledgeBase.getExternalId(), request());

        assertThat(capturedUserPrompt(context -> "rag".equals(context.purpose())))
                .contains("--- [Source: Redis.pdf, Section: Guide > Redis] ---");
        assertThat(response.getRetrievedChunkCount()).isEqualTo(1);
        assertThat(response.getSources()).singleElement().satisfies(source -> {
            assertThat(source.getChunkIndex()).isEqualTo(0);
            assertThat(source.getSimilarity()).isEqualTo(0.91D);
        });
        assertThat(response.getConfidence()).isEqualTo(0.91D);
    }

    @Test
    void askQuestion_RerankEnabled_RerankFails_DegradesToFusedChunks() {
        rerankConfigRef.set(rerankConfig());
        when(preRerankCandidateBuilder.build(any(), anyInt())).thenReturn(List.of(preCandidate()));
        when(chunkRerankService.rerank(any(), anyString(), any(), any(), any())).thenReturn(
                new ChunkRerankService.ChunkRerankResult(List.of(), true, "timeout")
        );

        service.askQuestion("owner", knowledgeBase.getExternalId(), request());

        assertThat(capturedUserPrompt(context -> "rag".equals(context.purpose())))
                .contains("[Document: Redis.pdf, Chunk: 0]");
    }

    @Test
    void askQuestion_RerankEnabled_DegradedInvocation_RecordsFallbackRecoveredUsage() {
        rerankConfigRef.set(rerankConfig());
        when(preRerankCandidateBuilder.build(any(), anyInt())).thenReturn(List.of(preCandidate()));
        AiInvocationContext rerankInvocationContext = new AiInvocationContext(
                "rerank-invocation",
                "kb_query_rerank",
                com.josh.interviewj.usage.model.UsageFamily.RERANK,
                "KB_QUERY_CREDITS",
                true,
                Map.of()
        );
        AiInvocationResult rerankInvocationResult = AiInvocationResult.fromRerank(
                "rerank-provider",
                new com.josh.interviewj.ragqa.model.RerankResponse(
                        "model",
                        10,
                        List.of(new com.josh.interviewj.ragqa.model.RerankResponse.ScoredDocument(0, 0.9D)),
                        10
                )
        );
        when(chunkRerankService.rerank(any(), anyString(), any(), any(), any())).thenReturn(
                new ChunkRerankService.ChunkRerankResult(
                        List.of(),
                        true,
                        "timeout",
                        List.of(new ChunkRerankService.RerankInvocation(
                                "primary",
                                "rerank-provider",
                                rerankInvocationResult.rerankResponse(),
                                rerankInvocationContext,
                                rerankInvocationResult
                        ))
                )
        );

        service.askQuestion("owner", knowledgeBase.getExternalId(), request());

        verify(aiOperationGateway, atLeastOnce()).submitInvocationOutcome(
                any(),
                org.mockito.ArgumentMatchers.argThat(context -> context != null && "kb_query_rerank".equals(context.purpose())),
                any(),
                eq(com.josh.interviewj.llm.gateway.dto.ExecutionDisposition.EXECUTED),
                eq(com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE),
                eq("kb_query_fallback_recovered")
        );
    }

    @Test
    void askQuestion_RerankRouteInvalid_DegradesToFusedChunks() {
        invalidRerankReasonRef.set("Rerank provider secret is invalid for purpose: kb_query_rerank");

        KnowledgeBaseQueryResponse response = service.askQuestion("owner", knowledgeBase.getExternalId(), request());

        verify(preRerankCandidateBuilder, never()).build(any(), anyInt());
        assertThat(capturedUserPrompt(context -> "rag".equals(context.purpose())))
                .contains("[Document: Redis.pdf, Chunk: 0]");
        assertThat(response.getRetrievedChunkCount()).isEqualTo(1);
        assertThat(response.getSources()).hasSize(1);
    }

    @Test
    void askQuestion_RerankEnabled_ContextAssemblyDisabled_DegradesToSeedChunks() {
        rerankConfigRef.set(rerankConfig());
        contextAssemblyProperties.setEnabled(false);
        when(preRerankCandidateBuilder.build(any(), anyInt())).thenReturn(List.of(preCandidate()));
        when(chunkRerankService.rerank(any(), anyString(), any(), any(), any())).thenReturn(
                new ChunkRerankService.ChunkRerankResult(List.of(rankedSeed()), false, "none")
        );

        KnowledgeBaseQueryResponse response = service.askQuestion("owner", knowledgeBase.getExternalId(), request());

        assertThat(capturedUserPrompt(context -> "rag".equals(context.purpose())))
                .contains("[Document: Redis.pdf, Chunk: 0]");
        assertThat(response.getConfidence()).isEqualTo(0.91D);
    }

    @Test
    void askQuestion_RerankEnabled_NoContextBudget_DegradesToFusedChunks() {
        rerankConfigRef.set(rerankConfig());
        contextAssemblyProperties.setEnabled(true);
        contextSelectionProperties.setTokenBudget(1);
        when(preRerankCandidateBuilder.build(any(), anyInt())).thenReturn(List.of(preCandidate()));
        when(chunkRerankService.rerank(any(), anyString(), any(), any(), any())).thenReturn(
                new ChunkRerankService.ChunkRerankResult(List.of(rankedSeed()), false, "none")
        );
        when(contextBlockAssembler.assemble(any())).thenReturn(
                new ContextBlockAssemblyResult(List.of(selectedBlock()), 0, 0, 0, false, "none")
        );
        when(coverageAwareContextSelector.select(any(), anyInt())).thenReturn(
                ContextAssemblyResult.degraded("no_available_context_budget")
        );

        KnowledgeBaseQueryResponse response = service.askQuestion("owner", knowledgeBase.getExternalId(), request());

        String ragPrompt = capturedUserPrompt(context -> "rag".equals(context.purpose()));
        assertThat(ragPrompt).contains("[Document: Redis.pdf, Chunk: 0]");
        assertThat(ragPrompt).doesNotContain("--- [Source:");
        assertThat(response.getRetrievedChunkCount()).isEqualTo(1);
        assertThat(response.getConfidence()).isEqualTo(0.91D);
    }

    @Test
    void askQuestion_RerankEnabled_SelectorThrows_DegradesToBlocksByScore() {
        rerankConfigRef.set(rerankConfig());
        contextAssemblyProperties.setEnabled(true);
        when(preRerankCandidateBuilder.build(any(), anyInt())).thenReturn(List.of(preCandidate()));
        when(chunkRerankService.rerank(any(), anyString(), any(), any(), any())).thenReturn(
                new ChunkRerankService.ChunkRerankResult(List.of(rankedSeed()), false, "none")
        );
        when(contextBlockAssembler.assemble(any())).thenReturn(
                new ContextBlockAssemblyResult(List.of(selectedBlock()), 0, 0, 0, false, "none")
        );
        when(coverageAwareContextSelector.select(any(), anyInt())).thenThrow(new RuntimeException("selector failed"));

        KnowledgeBaseQueryResponse response = service.askQuestion("owner", knowledgeBase.getExternalId(), request());

        assertThat(capturedUserPrompt(context -> "rag".equals(context.purpose())))
                .contains("--- [Source: Redis.pdf, Section: Guide > Redis] ---");
        assertThat(response.getRetrievedChunkCount()).isEqualTo(1);
        assertThat(response.getConfidence()).isEqualTo(0.91D);
    }

    @Test
    void askQuestion_RerankEnabled_AssemblerThrows_DegradesToSeedChunks() {
        rerankConfigRef.set(rerankConfig());
        contextAssemblyProperties.setEnabled(true);
        when(preRerankCandidateBuilder.build(any(), anyInt())).thenReturn(List.of(preCandidate()));
        when(chunkRerankService.rerank(any(), anyString(), any(), any(), any())).thenReturn(
                new ChunkRerankService.ChunkRerankResult(List.of(rankedSeed()), false, "none")
        );
        when(contextBlockAssembler.assemble(any())).thenThrow(new RuntimeException("assembly failed"));

        KnowledgeBaseQueryResponse response = service.askQuestion("owner", knowledgeBase.getExternalId(), request());

        String ragPrompt = capturedUserPrompt(context -> "rag".equals(context.purpose()));
        assertThat(ragPrompt).contains("[Document: Redis.pdf, Chunk: 0]");
        assertThat(ragPrompt).doesNotContain("--- [Source:");
        assertThat(response.getRetrievedChunkCount()).isEqualTo(1);
        assertThat(response.getConfidence()).isEqualTo(0.91D);
    }

    private String capturedUserPrompt(Predicate<AiInvocationContext> predicate) {
        ArgumentCaptor<AiInvocationContext> contextCaptor = ArgumentCaptor.forClass(AiInvocationContext.class);
        ArgumentCaptor<AiInvocationInput> inputCaptor = ArgumentCaptor.forClass(AiInvocationInput.class);
        verify(aiOperationGateway, atLeastOnce()).executeInvocation(any(), contextCaptor.capture(), inputCaptor.capture());
        List<AiInvocationContext> contexts = contextCaptor.getAllValues();
        List<AiInvocationInput> inputs = inputCaptor.getAllValues();
        for (int index = 0; index < contexts.size(); index++) {
            AiInvocationContext context = contexts.get(index);
            if (context != null && predicate.test(context)) {
                return inputs.get(index).userPrompt();
            }
        }
        throw new AssertionError("未捕获到匹配的 gateway invocation");
    }

    private KnowledgeBaseQueryAskRequest request() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis?");
        request.setTopK(5);
        request.setIncludeSources(true);
        return request;
    }

    private PreRerankCandidate preCandidate() {
        return new PreRerankCandidate(
                99L,
                UUID.randomUUID(),
                "Redis.pdf",
                0,
                "chunk-content",
                0.91D,
                0.91D,
                1,
                false,
                true,
                false,
                Set.of(new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.DENSE)),
                "{\"sectionPath\":[\"Guide\",\"Redis\"]}"
        );
    }

    private RankedChunkCandidate rankedSeed() {
        return new RankedChunkCandidate(
                99L,
                UUID.randomUUID(),
                "Redis.pdf",
                0,
                "chunk-content",
                "{\"sectionPath\":[\"Guide\",\"Redis\"]}",
                Set.of(new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.DENSE)),
                0.91D,
                0.91D,
                0.91D
        );
    }

    private ContextBlock selectedBlock() {
        return new ContextBlock(
                99L,
                UUID.randomUUID(),
                "Redis.pdf",
                List.of("Guide", "Redis"),
                List.of(0),
                List.of(0, 1),
                "block-content",
                60,
                0.91D,
                Map.of(),
                Set.of(new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.DENSE)),
                ContextBlock.AssemblyStrategy.SECTION_PRIORITY
        );
    }

    private ChunkSearchRepository.CompletedChunkProjection projection(String content, int chunkIndex, double similarity) {
        UUID documentExternalId = UUID.randomUUID();
        return new ChunkSearchRepository.CompletedChunkProjection() {
            @Override
            public UUID getDocumentExternalId() {
                return documentExternalId;
            }

            @Override
            public String getDocumentName() {
                return "Redis.pdf";
            }

            @Override
            public Long getDocumentId() {
                return 99L;
            }

            @Override
            public Integer getChunkIndex() {
                return chunkIndex;
            }

            @Override
            public String getContent() {
                return content;
            }

            @Override
            public String getMetadata() {
                return "{\"sectionPath\":[\"Guide\",\"Redis\"]}";
            }

            @Override
            public Double getSimilarity() {
                return similarity;
            }
        };
    }

    private DatabaseRerankConfig rerankConfig() {
        return new DatabaseRerankConfig(
                "kb_query_rerank",
                "rerank-provider",
                "https://rerank.example.com/v1",
                "rerank-secret",
                "qwen-rerank",
                3_000,
                24,
                10,
                0.1D,
                true
        );
    }

    private QueryEmbeddingService.EmbeddingExecutionResult embeddingExecution(String invocationId, String queryText) {
        com.josh.interviewj.llm.core.EmbeddingResponse response = new com.josh.interviewj.llm.core.EmbeddingResponse(
                new float[]{0.1f, 0.2f},
                "embedding_provider",
                "text-embedding-v4"
        );
        return new QueryEmbeddingService.EmbeddingExecutionResult(
                response,
                new AiInvocationContext(
                        invocationId,
                        "kb_query_embedding",
                        com.josh.interviewj.usage.model.UsageFamily.EMBEDDING,
                        "KB_QUERY_CREDITS",
                        false,
                        Map.of("query", queryText)
                ),
                AiInvocationResult.fromEmbedding(response)
        );
    }
}
