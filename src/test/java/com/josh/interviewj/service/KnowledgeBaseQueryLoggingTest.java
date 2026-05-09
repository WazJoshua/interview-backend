package com.josh.interviewj.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.routing.DatabaseLlmRouteSnapshotService;
import com.josh.interviewj.ragqa.config.ContextAssemblyProperties;
import com.josh.interviewj.ragqa.config.ContextSelectionProperties;
import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.ragqa.dto.request.KnowledgeBaseQueryAskRequest;
import com.josh.interviewj.ragqa.model.ContextAssemblyResult;
import com.josh.interviewj.ragqa.model.ContextBlockAssemblyResult;
import com.josh.interviewj.ragqa.model.DatabaseRerankConfig;
import com.josh.interviewj.ragqa.model.PreRerankCandidate;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RankedChunkCandidate;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievalProvenance;
import com.josh.interviewj.ragqa.repository.ChunkSearchRepository;
import com.josh.interviewj.ragqa.repository.ChunkSparseSearchRepository;
import com.josh.interviewj.ragqa.service.ChunkRerankService;
import com.josh.interviewj.ragqa.service.ContextBlockAssembler;
import com.josh.interviewj.ragqa.service.CoverageAwareContextSelector;
import com.josh.interviewj.ragqa.service.DatabaseRerankConfigResolver;
import com.josh.interviewj.ragqa.service.KnowledgeBaseQueryService;
import com.josh.interviewj.ragqa.service.PreRerankCandidateBuilder;
import com.josh.interviewj.ragqa.service.QueryEmbeddingService;
import com.josh.interviewj.ragqa.service.QueryNormalizationService;
import com.josh.interviewj.ragqa.service.QueryProfileDetector;
import com.josh.interviewj.ragqa.service.QueryRewriteService;
import com.josh.interviewj.ragqa.service.QueryUnderstandingService;
import com.josh.interviewj.ragqa.service.RagQaChatSessionService;
import com.josh.interviewj.ragqa.service.RetrievalPlanBuilder;
import com.josh.interviewj.ragqa.service.RetrievalResultFusionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseQueryLoggingTest {

    @Mock
    private KnowledgeBaseAccessService knowledgeBaseAccessService;

    @Mock
    private QueryEmbeddingService queryEmbeddingService;

    @Mock
    private ChunkSearchRepository chunkSearchRepository;

    @Mock
    private ChunkSparseSearchRepository chunkSparseSearchRepository;

    @Mock
    private KbDocumentRepository kbDocumentRepository;

    @Mock
    private RagQaChatSessionService ragQaChatSessionService;

    @Test
    void askQuestion_RerankSelectorReturnsEmptyBlocks_LogsDegradedTrue() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        QueryUnderstandingProperties properties = new QueryUnderstandingProperties();
        QueryUnderstandingService queryUnderstandingService = new QueryUnderstandingService(
                properties,
                new QueryNormalizationService(properties),
                new QueryProfileDetector(),
                new QueryRewriteService(mockRewriteGateway(), objectMapper, properties)
        );
        ChatProperties chatProperties = new ChatProperties();
        ContextAssemblyProperties contextAssemblyProperties = new ContextAssemblyProperties();
        ContextSelectionProperties contextSelectionProperties = new ContextSelectionProperties();
        PreRerankCandidateBuilder preRerankCandidateBuilder = mock(PreRerankCandidateBuilder.class);
        ChunkRerankService chunkRerankService = mock(ChunkRerankService.class);
        ContextBlockAssembler contextBlockAssembler = mock(ContextBlockAssembler.class);
        CoverageAwareContextSelector coverageAwareContextSelector = mock(CoverageAwareContextSelector.class);
        DatabaseRerankConfigResolver databaseRerankConfigResolver = new DatabaseRerankConfigResolver(
                () -> new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of("kb_query_rerank", new DatabaseRerankConfig(
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
                        )),
                        Map.of()
                )
        );
        lenient().when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), anyString()))
                .thenAnswer(invocation -> embeddingExecution(
                        invocation.getArgument(1, String.class),
                        invocation.getArgument(2, String.class)
                ));

        KnowledgeBaseQueryService service = new KnowledgeBaseQueryService(
                knowledgeBaseAccessService,
                queryUnderstandingService,
                new RetrievalPlanBuilder(properties),
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
                mockQueryGateway()
        );

        org.springframework.test.util.ReflectionTestUtils.setField(service, "defaultTopK", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxTopK", 20);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sourceContentMaxChars", 500);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sparseEnabled", false);

        KnowledgeBase knowledgeBase = KnowledgeBase.builder()
                .id(10L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .name("KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ChatSession chatSession = ChatSession.builder()
                .id(20L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(knowledgeBase.getExternalId())
                .status(ChatSessionStatus.ACTIVE)
                .build();
        ChatTurnWriteResult writeResult = new ChatTurnWriteResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        when(knowledgeBaseAccessService.requireQueryableKnowledgeBase("owner", knowledgeBase.getExternalId())).thenReturn(knowledgeBase);
        when(ragQaChatSessionService.resolveContext(eq("owner"), eq(knowledgeBase), any()))
                .thenReturn(new RagQaChatSessionService.RagQaChatSessionContext(
                        chatSession,
                        new ChatContextWindow(List.of(), false, 0, 0)
                ));
        when(ragQaChatSessionService.persistTurn(eq(chatSession), any(), any(), any(), any(), eq(1))).thenReturn(writeResult);
        lenient().when(queryEmbeddingService.embedQuery("Redis?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("chunk", 0, 0.91D)));
        when(preRerankCandidateBuilder.build(any(), anyInt())).thenReturn(List.of(new PreRerankCandidate(
                99L,
                UUID.randomUUID(),
                "Redis.pdf",
                0,
                "chunk",
                0.91D,
                0.91D,
                1,
                false,
                true,
                false,
                Set.of(new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.DENSE)),
                "{\"sectionPath\":[\"Guide\",\"Redis\"]}"
        )));
        when(chunkRerankService.rerank(any(), any(), any(), any(), any())).thenReturn(
                new ChunkRerankService.ChunkRerankResult(List.of(new RankedChunkCandidate(
                        99L,
                        UUID.randomUUID(),
                        "Redis.pdf",
                        0,
                        "chunk",
                        "{\"sectionPath\":[\"Guide\",\"Redis\"]}",
                        Set.of(new RetrievalProvenance(QueryVariant.ORIGINAL, RetrievalMode.DENSE)),
                        0.91D,
                        0.91D,
                        0.91D
                )), false, "none")
        );
        when(contextBlockAssembler.assemble(any())).thenReturn(
                new ContextBlockAssemblyResult(List.of(), 0, 0, 0, false, "none")
        );
        when(coverageAwareContextSelector.select(any(), anyInt())).thenReturn(
                ContextAssemblyResult.degraded("no_blocks_available")
        );

        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeBaseQueryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
            request.setQuestion("Redis?");
            request.setTopK(5);
            service.askQuestion("owner", knowledgeBase.getExternalId(), request);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("rerank_enabled=true")
                            && message.contains("rerank_degraded=true")
                            && message.contains("rerank_degraded_reason=no_blocks_available"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void askQuestion_Success_LogsStructuredChatFields() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        QueryUnderstandingProperties properties = new QueryUnderstandingProperties();
        QueryUnderstandingService queryUnderstandingService = new QueryUnderstandingService(
                properties,
                new QueryNormalizationService(properties),
                new QueryProfileDetector(),
                new QueryRewriteService(mockRewriteGateway(), objectMapper, properties)
        );
        ChatProperties chatProperties = new ChatProperties();
        lenient().when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), anyString()))
                .thenAnswer(invocation -> embeddingExecution(
                        invocation.getArgument(1, String.class),
                        invocation.getArgument(2, String.class)
                ));
        KnowledgeBaseQueryService service = new KnowledgeBaseQueryService(
                knowledgeBaseAccessService,
                queryUnderstandingService,
                new RetrievalPlanBuilder(properties),
                queryEmbeddingService,
                chunkSearchRepository,
                chunkSparseSearchRepository,
                kbDocumentRepository,
                objectMapper,
                new RetrievalResultFusionService(),
                Executors.newSingleThreadExecutor(),
                ragQaChatSessionService,
                chatProperties,
                mockQueryGateway()
        );

        org.springframework.test.util.ReflectionTestUtils.setField(service, "defaultTopK", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxTopK", 20);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sourceContentMaxChars", 500);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sparseEnabled", false);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "forceSparseWithoutLiteralSignal", false);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sparseReadyVersion", "HYBRID_SPARSE_V1");

        KnowledgeBase knowledgeBase = KnowledgeBase.builder()
                .id(10L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .name("KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ChatSession chatSession = ChatSession.builder()
                .id(20L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(knowledgeBase.getExternalId())
                .status(ChatSessionStatus.ACTIVE)
                .build();
        ChatTurnWriteResult writeResult = new ChatTurnWriteResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        when(knowledgeBaseAccessService.requireQueryableKnowledgeBase("owner", knowledgeBase.getExternalId())).thenReturn(knowledgeBase);
        when(ragQaChatSessionService.resolveContext(eq("owner"), eq(knowledgeBase), any()))
                .thenReturn(new RagQaChatSessionService.RagQaChatSessionContext(
                        chatSession,
                        new ChatContextWindow(List.of(), false, 0, 0)
                ));
        when(ragQaChatSessionService.persistTurn(eq(chatSession), any(), any(), any(), any(), eq(1))).thenReturn(writeResult);
        lenient().when(queryEmbeddingService.embedQuery("Redis?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("chunk", 0, 0.91D)));

        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeBaseQueryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
            request.setQuestion("Redis?");
            request.setTopK(5);
            service.askQuestion("owner", knowledgeBase.getExternalId(), request);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("chat_session_id=" + writeResult.chatSessionId())
                            && message.contains("user_message_id=" + writeResult.userMessageId())
                            && message.contains("assistant_message_id=" + writeResult.assistantMessageId())
                            && message.contains("recent_window_message_count=0")
                            && message.contains("recent_window_truncated=false"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void askQuestion_HybridSuccess_LogsHybridObservabilityFields() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        QueryUnderstandingProperties properties = new QueryUnderstandingProperties();
        QueryUnderstandingService queryUnderstandingService = new QueryUnderstandingService(
                properties,
                new QueryNormalizationService(properties),
                new QueryProfileDetector(),
                new QueryRewriteService(mockRewriteGateway(), objectMapper, properties)
        );
        ChatProperties chatProperties = new ChatProperties();
        lenient().when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), anyString()))
                .thenAnswer(invocation -> embeddingExecution(
                        invocation.getArgument(1, String.class),
                        invocation.getArgument(2, String.class)
                ));
        KnowledgeBaseQueryService service = new KnowledgeBaseQueryService(
                knowledgeBaseAccessService,
                queryUnderstandingService,
                new RetrievalPlanBuilder(properties),
                queryEmbeddingService,
                chunkSearchRepository,
                chunkSparseSearchRepository,
                kbDocumentRepository,
                objectMapper,
                new RetrievalResultFusionService(),
                Executors.newSingleThreadExecutor(),
                ragQaChatSessionService,
                chatProperties,
                mockQueryGateway()
        );

        org.springframework.test.util.ReflectionTestUtils.setField(service, "defaultTopK", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxTopK", 20);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sourceContentMaxChars", 500);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sparseEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "forceSparseWithoutLiteralSignal", false);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sparseReadyVersion", "HYBRID_SPARSE_V1");

        KnowledgeBase knowledgeBase = KnowledgeBase.builder()
                .id(10L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .name("KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ChatSession chatSession = ChatSession.builder()
                .id(20L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(knowledgeBase.getExternalId())
                .status(ChatSessionStatus.ACTIVE)
                .build();
        ChatTurnWriteResult writeResult = new ChatTurnWriteResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        when(knowledgeBaseAccessService.requireQueryableKnowledgeBase("owner", knowledgeBase.getExternalId())).thenReturn(knowledgeBase);
        when(ragQaChatSessionService.resolveContext(eq("owner"), eq(knowledgeBase), any()))
                .thenReturn(new RagQaChatSessionService.RagQaChatSessionContext(
                        chatSession,
                        new ChatContextWindow(List.of(), false, 0, 0)
                ));
        when(ragQaChatSessionService.persistTurn(eq(chatSession), any(), any(), any(), any(), eq(1))).thenReturn(writeResult);
        when(kbDocumentRepository.existsCompletedDocumentWithoutSparseReady(knowledgeBase.getId(), "HYBRID_SPARSE_V1")).thenReturn(false);
        lenient().when(queryEmbeddingService.embedQuery("AUTH_001")).thenReturn(new float[]{0.1f, 0.2f});
        doReturn(List.of(projection("dense", 0, 0.91D)))
                .when(chunkSearchRepository)
                .searchCompletedChunks(eq(knowledgeBase.getExternalId()), eq(1L), eq("[0.1,0.2]"), anyInt());
        when(chunkSparseSearchRepository.searchCompletedChunksSparse(
                eq(knowledgeBase.getExternalId()),
                eq(1L),
                any(),
                any(),
                any(),
                eq("HYBRID_SPARSE_V1"),
                anyDouble(),
                anyDouble(),
                anyDouble(),
                anyInt()
        )).thenReturn(List.of(sparseProjection("sparse", 0, 0.5D, 2.5D, 2.0D, 5.0D)));

        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeBaseQueryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
            request.setQuestion("AUTH_001");
            request.setTopK(5);
            service.askQuestion("owner", knowledgeBase.getExternalId(), request);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("kb_sparse_ready=true")
                            && message.contains("sparse_candidate_count=1")
                            && message.contains("sparse_selected_count=")
                            && message.contains("sparse_only_rescue_count=")
                            && message.contains("cross_branch_mismatch_count=")
                            && message.contains("literal_signal_types="));
        } finally {
            logger.detachAppender(appender);
        }
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
                return "{\"sectionPath\":[\"Redis\"]}";
            }

            @Override
            public Double getSimilarity() {
                return similarity;
            }
        };
    }

    private ChunkSparseSearchRepository.SparseChunkProjection sparseProjection(
            String content,
            int chunkIndex,
            double contentRank,
            double entityRank,
            double exactBoost,
            double finalSparseScore
    ) {
        UUID documentExternalId = UUID.randomUUID();
        return new ChunkSparseSearchRepository.SparseChunkProjection() {
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
                return "{\"sectionPath\":[\"Redis\"]}";
            }

            @Override
            public Double getContentRank() {
                return contentRank;
            }

            @Override
            public Double getEntityRank() {
                return entityRank;
            }

            @Override
            public Double getExactBoost() {
                return exactBoost;
            }

            @Override
            public Double getFinalSparseScore() {
                return finalSparseScore;
            }
        };
    }

    private AiOperationGateway mockRewriteGateway() {
        AiOperationGateway gateway = mock(AiOperationGateway.class);
        lenient().when(gateway.executeInvocation(any(), any(), any())).thenAnswer(invocation -> {
            AiInvocationInput input = invocation.getArgument(2);
            return AiInvocationResult.fromChat(new LlmResponse(
                    "{\"rewrittenQuery\":\"" + input.userPrompt() + "\"}",
                    "mock-provider",
                    "mock-model",
                    new ProviderUsage(com.josh.interviewj.usage.model.UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
            ));
        });
        return gateway;
    }

    private AiOperationGateway mockQueryGateway() {
        AiOperationGateway gateway = mock(AiOperationGateway.class);
        lenient().when(gateway.prepareOperation(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().doNothing().when(gateway).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
        lenient().when(gateway.executeInvocation(any(), any(), any())).thenAnswer(invocation -> {
            com.josh.interviewj.llm.gateway.dto.AiInvocationContext invocationContext = invocation.getArgument(1);
            AiInvocationInput input = invocation.getArgument(2);
            if (invocationContext != null && "kb_query_rewrite".equals(invocationContext.purpose())) {
                return AiInvocationResult.fromChat(new LlmResponse(
                        "{\"rewrittenQuery\":\"" + String.valueOf(input.userPrompt()) + "\"}",
                        "mock-provider",
                        "mock-model",
                        new ProviderUsage(com.josh.interviewj.usage.model.UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                ));
            }
            return AiInvocationResult.fromChat(new LlmResponse(
                    "{\"answer\":\"A\",\"confidence\":0.8}",
                    "mock-provider",
                    "mock-model",
                    new ProviderUsage(com.josh.interviewj.usage.model.UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
            ));
        });
        return gateway;
    }

    private QueryEmbeddingService.EmbeddingExecutionResult embeddingExecution(String invocationId, String queryText) {
        com.josh.interviewj.llm.core.EmbeddingResponse response = new com.josh.interviewj.llm.core.EmbeddingResponse(
                new float[]{0.1f, 0.2f},
                "embedding_provider",
                "text-embedding-v4"
        );
        return new QueryEmbeddingService.EmbeddingExecutionResult(
                response,
                new com.josh.interviewj.llm.gateway.dto.AiInvocationContext(
                        invocationId,
                        "kb_query_embedding",
                        com.josh.interviewj.usage.model.UsageFamily.EMBEDDING,
                        "KB_QUERY_CREDITS",
                        false,
                        Map.of("query", queryText)
                ),
                com.josh.interviewj.llm.gateway.dto.AiInvocationResult.fromEmbedding(response)
        );
    }
}
