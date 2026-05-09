package com.josh.interviewj.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.chat.config.ChatProperties;
import com.josh.interviewj.chat.dto.ChatContextWindow;
import com.josh.interviewj.chat.dto.ChatTurnWriteResult;
import com.josh.interviewj.chat.model.ChatDomainRefType;
import com.josh.interviewj.chat.model.ChatDomainType;
import com.josh.interviewj.chat.model.ChatSession;
import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseAccessService;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.llm.core.LlmClient;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.ragqa.dto.request.KnowledgeBaseQueryAskRequest;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseQueryResponse;
import com.josh.interviewj.ragqa.config.QueryUnderstandingProperties;
import com.josh.interviewj.ragqa.repository.ChunkSearchRepository;
import com.josh.interviewj.ragqa.repository.ChunkSparseSearchRepository;
import com.josh.interviewj.ragqa.service.KnowledgeBaseQueryService;
import com.josh.interviewj.ragqa.service.QueryNormalizationService;
import com.josh.interviewj.ragqa.service.QueryProfileDetector;
import com.josh.interviewj.ragqa.service.QueryRewriteService;
import com.josh.interviewj.ragqa.service.QueryUnderstandingService;
import com.josh.interviewj.ragqa.service.QueryEmbeddingService;
import com.josh.interviewj.ragqa.service.RagQaChatSessionService;
import com.josh.interviewj.ragqa.service.RetrievalPlanBuilder;
import com.josh.interviewj.ragqa.service.RetrievalResultFusionService;
import com.josh.interviewj.usage.service.CreditsGuardService;
import com.josh.interviewj.usage.service.UsageContextFactory;
import com.josh.interviewj.usage.service.UsageRecordingFacade;
import com.josh.interviewj.usage.service.UsageFailureCompensationService;
import com.josh.interviewj.usage.service.UsageOperationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeBaseQueryServiceTest {

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
    private LlmClient llmClient;

    @Mock
    private RagQaChatSessionService ragQaChatSessionService;

    @Mock
    private UsageRecordingFacade usageRecordingFacade;

    @Mock
    private UsageFailureCompensationService usageFailureCompensationService;

    @Mock
    private UsageContextFactory usageContextFactory;

    @Mock
    private CreditsGuardService creditsGuardService;

    @Mock
    private AiOperationGateway aiOperationGateway;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private KnowledgeBaseQueryService knowledgeBaseQueryService;
    private QueryUnderstandingProperties queryUnderstandingProperties;
    private ChatProperties chatProperties;
    private ExecutorService executorService;

    private User testUser;
    private KnowledgeBase knowledgeBase;
    private ChatSession chatSession;

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(2);
        chatProperties = new ChatProperties();
        queryUnderstandingProperties = new QueryUnderstandingProperties();
        QueryUnderstandingProperties.AliasEntry aofAlias = new QueryUnderstandingProperties.AliasEntry();
        aofAlias.setAlias("aof");
        aofAlias.setCanonical("append only file");
        QueryUnderstandingProperties.AliasEntry jwtAlias = new QueryUnderstandingProperties.AliasEntry();
        jwtAlias.setAlias("jwt");
        jwtAlias.setCanonical("json web token");
        queryUnderstandingProperties.setAliasDictionary(List.of(aofAlias, jwtAlias));

        QueryRewriteService queryRewriteService = new QueryRewriteService(
                aiOperationGateway,
                objectMapper,
                queryUnderstandingProperties
        );
        lenient().when(aiOperationGateway.executeInvocation(any(), any(), any())).thenAnswer(invocation -> {
            com.josh.interviewj.llm.gateway.dto.AiInvocationContext invocationContext = invocation.getArgument(1);
            AiInvocationInput input = invocation.getArgument(2);
            if (invocationContext != null && "kb_query_rewrite".equals(invocationContext.purpose())) {
                String rewrittenQuery = String.valueOf(input.userPrompt());
                if (rewrittenQuery.contains("JWT 登录失败怎么办")) {
                    rewrittenQuery = "JWT 登录失败 json web token";
                } else if (rewrittenQuery.contains("AOF 持久化")) {
                    rewrittenQuery = "AOF 持久化 append only file";
                }
                return AiInvocationResult.fromChat(new LlmResponse(
                        "{\"rewrittenQuery\":\"" + rewrittenQuery + "\"}",
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
        lenient().when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), anyString()))
                .thenAnswer(invocation -> embeddingExecution(
                        invocation.getArgument(1, String.class),
                        invocation.getArgument(2, String.class)
                ));
        QueryUnderstandingService queryUnderstandingService = new QueryUnderstandingService(
                queryUnderstandingProperties,
                new QueryNormalizationService(queryUnderstandingProperties),
                new QueryProfileDetector(),
                queryRewriteService
        );
        knowledgeBaseQueryService = new KnowledgeBaseQueryService(
                knowledgeBaseAccessService,
                queryUnderstandingService,
                new RetrievalPlanBuilder(queryUnderstandingProperties),
                queryEmbeddingService,
                chunkSearchRepository,
                chunkSparseSearchRepository,
                kbDocumentRepository,
                objectMapper,
                new RetrievalResultFusionService(),
                executorService,
                ragQaChatSessionService,
                chatProperties,
                aiOperationGateway
        );
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "defaultTopK", 5);
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "maxTopK", 20);
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sourceContentMaxChars", 500);
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseEnabled", false);
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "forceSparseWithoutLiteralSignal", false);
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseReadyVersion", "HYBRID_SPARSE_V1");
        lenient().when(aiOperationGateway.prepareOperation(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().doNothing().when(aiOperationGateway).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
        lenient().when(aiOperationGateway.executeInvocation(any(), any(), any())).thenAnswer(invocation -> {
            com.josh.interviewj.llm.gateway.dto.AiInvocationContext invocationContext = invocation.getArgument(1);
            AiInvocationInput input = invocation.getArgument(2);
            if ("kb_query_rewrite".equals(invocationContext.purpose())) {
                return AiInvocationResult.fromChat(new LlmResponse(
                        "{\"rewrittenQuery\":\"" + input.userPrompt() + "\"}",
                        "mock-provider",
                        "mock-model",
                        new ProviderUsage(com.josh.interviewj.usage.model.UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                ));
            }
            if ("rag".equals(invocationContext.purpose())) {
                return AiInvocationResult.fromChat(new LlmResponse(
                        "{\"answer\":\"mock answer\",\"confidence\":0.8}",
                        "mock-provider",
                        "mock-model",
                        new ProviderUsage(com.josh.interviewj.usage.model.UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
                ));
            }
            throw new IllegalArgumentException("Unexpected gateway purpose: " + invocationContext.purpose());
        });

        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");

        knowledgeBase = KnowledgeBase.builder()
                .id(10L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .name("Java KB")
                .embeddingModel("text-embedding-v4")
                .vectorDimension(2048)
                .status(KnowledgeBaseStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        chatSession = ChatSession.builder()
                .id(88L)
                .externalId(UUID.randomUUID())
                .userId(1L)
                .domainType(ChatDomainType.RAG_QA)
                .domainRefType(ChatDomainRefType.KNOWLEDGE_BASE)
                .domainRefExternalId(knowledgeBase.getExternalId())
                .status(ChatSessionStatus.ACTIVE)
                .build();

        lenient().when(ragQaChatSessionService.resolveContext(eq("testuser"), eq(knowledgeBase), any()))
                .thenReturn(new RagQaChatSessionService.RagQaChatSessionContext(
                        chatSession,
                        new ChatContextWindow(List.of(), false, 0, 0)
                ));
        lenient().when(ragQaChatSessionService.persistTurn(eq(chatSession), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(new ChatTurnWriteResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void askQuestion_NoAvailableChunks_ThrowsKb004() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis persistence?");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQuery("Redis persistence?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5)).thenReturn(List.of());
        when(kbDocumentRepository.countByKbId(knowledgeBase.getId())).thenReturn(0L);

        BusinessException exception = assertThrows(BusinessException.class, () -> knowledgeBaseQueryService.askQuestion(
                "testuser",
                knowledgeBase.getExternalId(),
                request
        ));

        assertEquals("KB_004", exception.getErrorCode());
    }

    @Test
    void askQuestion_NoPermission_ThrowsAuth006() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis persistence?");

        when(knowledgeBaseAccessService.requireQueryableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenThrow(new BusinessException("AUTH_006", "Forbidden"));

        BusinessException exception = assertThrows(BusinessException.class, () -> knowledgeBaseQueryService.askQuestion(
                "testuser",
                knowledgeBase.getExternalId(),
                request
        ));

        assertEquals("AUTH_006", exception.getErrorCode());
    }

    @Test
    void askQuestion_WhenQueryUnderstandingDisabled_RevertsToRawQueryBaseline() {
        queryUnderstandingProperties.setEnabled(false);
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("AOF 持久化");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        doReturn(List.of(projection("Chunk content", 0, 0.92D)))
                .when(chunkSearchRepository)
                .searchCompletedChunks(eq(knowledgeBase.getExternalId()), eq(1L), eq("[0.1,0.2]"), anyInt());
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        verify(queryEmbeddingService).embedQueryWithUsage(any(), anyString(), eq("AOF 持久化"));
    }

    @Test
    void askQuestion_Phase1UsesNormalizedQueryForEmbeddingButRawQuestionForPrompt() {
        queryUnderstandingProperties.setEnabled(true);
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("AOF 持久化");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        doReturn(List.of(projection("Chunk content", 0, 0.92D)))
                .when(chunkSearchRepository)
                .searchCompletedChunks(eq(knowledgeBase.getExternalId()), eq(1L), eq("[0.1,0.2]"), anyInt());
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        verify(queryEmbeddingService).embedQueryWithUsage(any(), anyString(), eq("AOF 持久化 append only file"));
        ArgumentCaptor<AiInvocationInput> requestCaptor = ArgumentCaptor.forClass(AiInvocationInput.class);
        verify(aiOperationGateway, org.mockito.Mockito.atLeastOnce()).executeInvocation(any(), any(), requestCaptor.capture());
        AiInvocationInput ragInput = requestCaptor.getAllValues().stream()
                .filter(value -> value.userPrompt() != null && value.userPrompt().contains("Question:\n"))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertTrue(ragInput.userPrompt().contains("Question:\nAOF 持久化\n\nContext:\n"));
        assertTrue(!ragInput.userPrompt().contains("Question:\nAOF 持久化 append only file"));
    }

    @Test
    void askQuestion_Phase2UsesRewriteTextForRetrievalButKeepsRawPromptQuestion() {
        queryUnderstandingProperties.setEnabled(true);
        queryUnderstandingProperties.setRewriteEnabled(true);
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("JWT 登录失败怎么办");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 0, 0.92D)));
        when(llmClient.generateStructuredJson(any())).thenAnswer(invocation -> {
            com.josh.interviewj.llm.core.LlmRequest llmRequest = invocation.getArgument(0);
            if ("kb_query_rewrite".equals(llmRequest.purpose())) {
                return new LlmResponse("{\"rewrittenQuery\":\"JWT 登录失败 json web token\"}", "dispatcher_rc", "gpt");
            }
            return new LlmResponse("{\"answer\":\"A\"}", "dispatcher_rc", "gpt");
        });

        knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        ArgumentCaptor<String> embeddingCaptor = ArgumentCaptor.forClass(String.class);
        verify(queryEmbeddingService, org.mockito.Mockito.atLeastOnce()).embedQueryWithUsage(any(), anyString(), embeddingCaptor.capture());
        assertTrue(embeddingCaptor.getAllValues().stream().anyMatch(value -> value.contains("json web token")));
        ArgumentCaptor<AiInvocationInput> requestCaptor = ArgumentCaptor.forClass(AiInvocationInput.class);
        verify(aiOperationGateway, org.mockito.Mockito.atLeastOnce()).executeInvocation(any(), any(), requestCaptor.capture());
        assertTrue(requestCaptor.getAllValues().stream()
                .filter(value -> value.userPrompt() != null && value.userPrompt().contains("Question:\n"))
                .allMatch(value -> value.userPrompt().contains("Question:\nJWT 登录失败怎么办\n\nContext:\n")));
    }

    @Test
    void askQuestion_RewriteTimeout_FallsBackToNormalizedRetrieval() {
        queryUnderstandingProperties.setRewriteEnabled(true);
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("AOF 持久化");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 0, 0.92D)));
        when(llmClient.generateStructuredJson(any())).thenAnswer(invocation -> {
            com.josh.interviewj.llm.core.LlmRequest llmRequest = invocation.getArgument(0);
            if ("kb_query_rewrite".equals(llmRequest.purpose())) {
                throw new BusinessException("LLM_001", "LLM service call failed: TIMEOUT - upstream timeout");
            }
            return new LlmResponse("{\"answer\":\"A\"}", "dispatcher_rc", "gpt");
        });

        knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        verify(queryEmbeddingService).embedQueryWithUsage(any(), anyString(), eq("AOF 持久化 append only file"));
    }

    @Test
    void askQuestion_Success_RecordsRewriteEmbeddingAndRagUsage() {
        queryUnderstandingProperties.setRewriteEnabled(true);
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("JWT 登录失败怎么办");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), eq("JWT 登录失败 json web token")))
                .thenReturn(embeddingExecution("biz-1:embedding", "JWT 登录失败 json web token"));
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 0, 0.92D)));
        when(llmClient.generateStructuredJson(any())).thenAnswer(invocation -> {
            com.josh.interviewj.llm.core.LlmRequest llmRequest = invocation.getArgument(0);
            if ("kb_query_rewrite".equals(llmRequest.purpose())) {
                return new LlmResponse("{\"rewrittenQuery\":\"JWT 登录失败 json web token\"}", "dispatcher_rc", "gpt");
            }
            return new LlmResponse("{\"answer\":\"A\"}", "dispatcher_rc", "gpt");
        });
        knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        verify(aiOperationGateway, org.mockito.Mockito.atLeastOnce()).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void askQuestion_Success_PersistsTurnBeforeRecordingUsage() {
        queryUnderstandingProperties.setRewriteEnabled(true);
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("JWT 登录失败怎么办");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), eq("JWT 登录失败 json web token")))
                .thenReturn(embeddingExecution("biz-1:embedding", "JWT 登录失败 json web token"));
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 0, 0.92D)));
        when(llmClient.generateStructuredJson(any())).thenAnswer(invocation -> {
            com.josh.interviewj.llm.core.LlmRequest llmRequest = invocation.getArgument(0);
            if ("kb_query_rewrite".equals(llmRequest.purpose())) {
                return new LlmResponse("{\"rewrittenQuery\":\"JWT 登录失败 json web token\"}", "dispatcher_rc", "gpt");
            }
            return new LlmResponse("{\"answer\":\"A\"}", "dispatcher_rc", "gpt");
        });
        knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        org.mockito.InOrder inOrder = inOrder(aiOperationGateway, ragQaChatSessionService);
        inOrder.verify(ragQaChatSessionService).persistTurn(eq(chatSession), anyString(), anyString(), any(), any(), anyInt());
        inOrder.verify(aiOperationGateway, org.mockito.Mockito.atLeastOnce()).submitInvocationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void askQuestion_WhenGuardRejects_DoesNotCallDownstreamProviders() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis persistence?");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        doThrow(new BusinessException(ErrorCode.USER_BILLING_001, "Insufficient billing balance"))
                .when(aiOperationGateway)
                .prepareOperation(any());

        BusinessException exception = assertThrows(BusinessException.class, () -> knowledgeBaseQueryService.askQuestion(
                "testuser",
                knowledgeBase.getExternalId(),
                request
        ));

        assertEquals(ErrorCode.USER_BILLING_001, exception.getErrorCode());
        verifyNoInteractions(queryEmbeddingService, chunkSearchRepository);
        verify(ragQaChatSessionService, never()).persistTurn(any(), anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    void askQuestion_WhenUsageRecordingFails_ThrowsAfterPersistingChatTurn() {
        queryUnderstandingProperties.setRewriteEnabled(true);
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("JWT 登录失败怎么办");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), eq("JWT 登录失败 json web token")))
                .thenReturn(embeddingExecution("biz-1:embedding", "JWT 登录失败 json web token"));
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 0, 0.92D)));
        when(llmClient.generateStructuredJson(any())).thenAnswer(invocation -> {
            com.josh.interviewj.llm.core.LlmRequest llmRequest = invocation.getArgument(0);
            if ("kb_query_rewrite".equals(llmRequest.purpose())) {
                return new LlmResponse("{\"rewrittenQuery\":\"JWT 登录失败 json web token\"}", "dispatcher_rc", "gpt");
            }
            return new LlmResponse("{\"answer\":\"A\"}", "dispatcher_rc", "gpt");
        });
        doThrow(new BusinessException(ErrorCode.USER_BILLING_001, "Insufficient billing balance"))
                .when(aiOperationGateway)
                .submitInvocationOutcome(any(), any(), any(), any(), any(), any());

        BusinessException exception = assertThrows(BusinessException.class, () -> knowledgeBaseQueryService.askQuestion(
                "testuser",
                knowledgeBase.getExternalId(),
                request
        ));

        assertEquals(ErrorCode.USER_BILLING_001, exception.getErrorCode());
        verify(ragQaChatSessionService).persistTurn(eq(chatSession), anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    void askQuestion_WhenPersistTurnFails_OnlyRecordsFailedOutcomes() {
        queryUnderstandingProperties.setRewriteEnabled(true);
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("JWT 登录失败怎么办");
        request.setTopK(5);
        request.setIncludeSources(true);
        BusinessOperationContext preparedContext = new BusinessOperationContext(
                "prepared-biz-1",
                1L,
                "KNOWLEDGE_BASE_QUERY",
                "prepared-kb-1",
                "kb_query",
                List.of("KB_QUERY_CREDITS"),
                Map.of("prepared", true)
        );

        stubAccessibleKnowledgeBase();
        doReturn(preparedContext).when(aiOperationGateway).prepareOperation(any());
        when(queryEmbeddingService.embedQueryWithUsage(any(), anyString(), eq("JWT 登录失败 json web token")))
                .thenReturn(embeddingExecution("biz-1:embedding", "JWT 登录失败 json web token"));
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 0, 0.92D)));
        when(ragQaChatSessionService.persistTurn(eq(chatSession), anyString(), anyString(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("persist failed"));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> knowledgeBaseQueryService.askQuestion(
                "testuser",
                knowledgeBase.getExternalId(),
                request
        ));

        assertEquals("persist failed", exception.getMessage());
        ArgumentCaptor<BusinessOperationContext> contextCaptor = ArgumentCaptor.forClass(BusinessOperationContext.class);
        ArgumentCaptor<InvocationUsageOutcome> outcomeCaptor = ArgumentCaptor.forClass(InvocationUsageOutcome.class);
        verify(aiOperationGateway, org.mockito.Mockito.atLeastOnce())
                .submitInvocationOutcome(contextCaptor.capture(), any(), any(), any(), outcomeCaptor.capture(), any());
        assertTrue(contextCaptor.getAllValues().stream().allMatch(context -> context == preparedContext));
        assertTrue(outcomeCaptor.getAllValues().stream()
                .allMatch(outcome -> outcome == InvocationUsageOutcome.FAILED_NON_CHARGEABLE));
    }

    @Test
    void askQuestion_DualBranchRewriteFailure_DegradesToOriginalOnly() {
        queryUnderstandingProperties.setRewriteEnabled(true);
        queryUnderstandingProperties.setDualBranchEnabled(true);
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("JWT 登录失败怎么办");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQueryWithUsage(
                any(),
                org.mockito.ArgumentMatchers.contains(":embedding:rewrite:"),
                anyString()
        ))
                .thenThrow(new BusinessException("LLM_001", "rewrite branch failed"));
        doReturn(List.of(projection("Chunk content", 0, 0.92D)))
                .when(chunkSearchRepository)
                .searchCompletedChunks(eq(knowledgeBase.getExternalId()), eq(1L), eq("[0.1,0.2]"), anyInt());
        when(llmClient.generateStructuredJson(any())).thenAnswer(invocation -> {
            com.josh.interviewj.llm.core.LlmRequest llmRequest = invocation.getArgument(0);
            if ("kb_query_rewrite".equals(llmRequest.purpose())) {
                return new LlmResponse("{\"rewrittenQuery\":\"JWT 登录失败 json web token\"}", "dispatcher_rc", "gpt");
            }
            return new LlmResponse("{\"answer\":\"A\"}", "dispatcher_rc", "gpt");
        });

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        assertEquals(1, response.getRetrievedChunkCount());
    }

    @Test
    void askQuestion_DualBranchOriginalFailure_RewriteOnlyStillSucceeds() {
        queryUnderstandingProperties.setRewriteEnabled(true);
        queryUnderstandingProperties.setDualBranchEnabled(true);
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("JWT 登录失败怎么办");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQueryWithUsage(
                any(),
                org.mockito.ArgumentMatchers.contains(":embedding:original:"),
                anyString()
        ))
                .thenThrow(new BusinessException("LLM_001", "original branch failed"));
        when(queryEmbeddingService.embedQueryWithUsage(
                any(),
                org.mockito.ArgumentMatchers.contains(":embedding:rewrite:"),
                anyString()
        ))
                .thenReturn(embeddingExecution("biz-1:embedding", "JWT 登录失败 json web token"));
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 8))
                .thenReturn(List.of(projection("Chunk content", 0, 0.92D)));
        when(llmClient.generateStructuredJson(any())).thenAnswer(invocation -> {
            com.josh.interviewj.llm.core.LlmRequest llmRequest = invocation.getArgument(0);
            if ("kb_query_rewrite".equals(llmRequest.purpose())) {
                return new LlmResponse("{\"rewrittenQuery\":\"JWT 登录失败 json web token\"}", "dispatcher_rc", "gpt");
            }
            return new LlmResponse("{\"answer\":\"A\"}", "dispatcher_rc", "gpt");
        });

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        assertEquals(1, response.getRetrievedChunkCount());
    }

    @Test
    void askQuestion_LiteralSignalDetected_ResolvesHybridEligibilityBeforePlanning() {
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseEnabled", true);

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("AUTH_001 spring.profiles.active --tests");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(kbDocumentRepository.existsCompletedDocumentWithoutSparseReady(knowledgeBase.getId(), "HYBRID_SPARSE_V1"))
                .thenReturn(false);
        when(queryEmbeddingService.embedQuery("AUTH_001 spring.profiles.active --tests")).thenReturn(new float[]{0.1f, 0.2f});
        doReturn(List.of(projection("Chunk content", 0, 0.92D)))
                .when(chunkSearchRepository)
                .searchCompletedChunks(eq(knowledgeBase.getExternalId()), eq(1L), eq("[0.1,0.2]"), anyInt());
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        assertEquals(1, response.getRetrievedChunkCount());
        verify(kbDocumentRepository).existsCompletedDocumentWithoutSparseReady(knowledgeBase.getId(), "HYBRID_SPARSE_V1");
    }

    @Test
    void askQuestion_SparseBranchTimeout_DegradesToDenseOnly() {
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseEnabled", true);
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseTimeoutMs", 50);

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("AUTH_001 spring.profiles.active");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(kbDocumentRepository.existsCompletedDocumentWithoutSparseReady(knowledgeBase.getId(), "HYBRID_SPARSE_V1"))
                .thenReturn(false);
        when(queryEmbeddingService.embedQuery("AUTH_001 spring.profiles.active")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 8))
                .thenReturn(List.of(projection("Dense chunk", 0, 0.92D)));
        AtomicBoolean sparseBranchEntered = new AtomicBoolean(false);
        when(chunkSparseSearchRepository.searchCompletedChunksSparse(
                eq(knowledgeBase.getExternalId()),
                eq(1L),
                anyString(),
                anyString(),
                anyString(),
                eq("HYBRID_SPARSE_V1"),
                anyDouble(),
                anyDouble(),
                anyDouble(),
                anyInt()
        )).thenAnswer(invocation -> {
            sparseBranchEntered.set(true);
            Thread.sleep(250L);
            return List.of(sparseProjection("Sparse chunk", 0, 0.5D, 2.5D, 2.0D, 5.0D));
        });
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        long startedAt = System.nanoTime();
        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertEquals(1, response.getRetrievedChunkCount());
        assertTrue(sparseBranchEntered.get());
        assertTrue(elapsedMs < 200, "sparse timeout should degrade before the slow branch completes");
        verify(chunkSparseSearchRepository).searchCompletedChunksSparse(
                eq(knowledgeBase.getExternalId()),
                eq(1L),
                anyString(),
                anyString(),
                anyString(),
                eq("HYBRID_SPARSE_V1"),
                anyDouble(),
                anyDouble(),
                anyDouble(),
                anyInt()
        );
    }

    @Test
    void askQuestion_DenseNoMatchAndSparseTimeout_ThrowsKb004InsteadOfRuntimeError() {
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseEnabled", true);
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseTimeoutMs", 50);

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("AUTH_001 spring.profiles.active");
        request.setTopK(5);
        request.setIncludeSources(true);

        CountDownLatch sparseBranchStarted = new CountDownLatch(1);
        CountDownLatch sparseBranchInterrupted = new CountDownLatch(1);
        stubAccessibleKnowledgeBase();
        when(kbDocumentRepository.existsCompletedDocumentWithoutSparseReady(knowledgeBase.getId(), "HYBRID_SPARSE_V1"))
                .thenReturn(false);
        when(kbDocumentRepository.countByKbId(knowledgeBase.getId())).thenReturn(1L);
        when(kbDocumentRepository.countByKbIdAndStatus(knowledgeBase.getId(), com.josh.interviewj.knowledgebase.model.KbDocumentStatus.COMPLETED))
                .thenReturn(1L);
        when(kbDocumentRepository.countSearchableChunksByKbId(knowledgeBase.getId())).thenReturn(3L);
        when(queryEmbeddingService.embedQuery("AUTH_001 spring.profiles.active")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 8))
                .thenReturn(List.of());
        when(chunkSparseSearchRepository.searchCompletedChunksSparse(
                eq(knowledgeBase.getExternalId()),
                eq(1L),
                anyString(),
                anyString(),
                anyString(),
                eq("HYBRID_SPARSE_V1"),
                anyDouble(),
                anyDouble(),
                anyDouble(),
                anyInt()
        )).thenAnswer(invocation -> {
            sparseBranchStarted.countDown();
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                sparseBranchInterrupted.countDown();
                return List.of();
            }
            return List.of();
        });

        BusinessException exception = assertThrows(BusinessException.class, () -> knowledgeBaseQueryService.askQuestion(
                "testuser",
                knowledgeBase.getExternalId(),
                request
        ));

        assertEquals("KB_004", exception.getErrorCode());
        assertTrue(awaitLatch(sparseBranchStarted, 1, TimeUnit.SECONDS));
        assertTrue(awaitLatch(sparseBranchInterrupted, 1, TimeUnit.SECONDS));
    }

    @Test
    void askQuestion_SparseBranchTimeout_CancelsSlowSparseWork() {
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseEnabled", true);
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseTimeoutMs", 50);

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("AUTH_001 spring.profiles.active");
        request.setTopK(5);
        request.setIncludeSources(true);

        CountDownLatch sparseBranchStarted = new CountDownLatch(1);
        CountDownLatch sparseBranchInterrupted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        stubAccessibleKnowledgeBase();
        when(kbDocumentRepository.existsCompletedDocumentWithoutSparseReady(knowledgeBase.getId(), "HYBRID_SPARSE_V1"))
                .thenReturn(false);
        when(queryEmbeddingService.embedQuery("AUTH_001 spring.profiles.active")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 8))
                .thenReturn(List.of(projection("Dense chunk", 0, 0.92D)));
        when(chunkSparseSearchRepository.searchCompletedChunksSparse(
                eq(knowledgeBase.getExternalId()),
                eq(1L),
                anyString(),
                anyString(),
                anyString(),
                eq("HYBRID_SPARSE_V1"),
                anyDouble(),
                anyDouble(),
                anyDouble(),
                anyInt()
        )).thenAnswer(invocation -> {
            sparseBranchStarted.countDown();
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interrupted.set(true);
                sparseBranchInterrupted.countDown();
                return List.of();
            }
            return List.of(sparseProjection("Sparse chunk", 0, 0.5D, 2.5D, 2.0D, 5.0D));
        });
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        long startedAt = System.nanoTime();
        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertEquals(1, response.getRetrievedChunkCount());
        assertTrue(awaitLatch(sparseBranchStarted, 1, TimeUnit.SECONDS));
        assertTrue(elapsedMs < 200, "sparse timeout should degrade before the slow branch completes");
        assertTrue(awaitLatch(sparseBranchInterrupted, 1, TimeUnit.SECONDS));
        assertTrue(interrupted.get(), "sparse timeout should request cancellation on the slow branch");
    }

    @Test
    void askQuestion_MultiLiteralSignal_UsesMultiLiteralSparseWeight() {
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseEnabled", true);
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseEntityWeightDefault", 3.0D);
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseEntityWeightSingleLiteral", 4.0D);
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseEntityWeightMultiLiteral", 5.0D);

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("AUTH_001 spring.profiles.active");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(kbDocumentRepository.existsCompletedDocumentWithoutSparseReady(knowledgeBase.getId(), "HYBRID_SPARSE_V1"))
                .thenReturn(false);
        when(queryEmbeddingService.embedQuery("AUTH_001 spring.profiles.active")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 8))
                .thenReturn(List.of(projection("Dense chunk", 0, 0.92D)));
        when(chunkSparseSearchRepository.searchCompletedChunksSparse(
                eq(knowledgeBase.getExternalId()),
                eq(1L),
                anyString(),
                anyString(),
                anyString(),
                eq("HYBRID_SPARSE_V1"),
                anyDouble(),
                eq(5.0D),
                anyDouble(),
                anyInt()
        )).thenReturn(List.of(sparseProjection("Sparse chunk", 0, 0.5D, 2.5D, 2.0D, 5.0D)));
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        assertEquals(1, response.getRetrievedChunkCount());
        verify(chunkSparseSearchRepository).searchCompletedChunksSparse(
                eq(knowledgeBase.getExternalId()),
                eq(1L),
                anyString(),
                anyString(),
                anyString(),
                eq("HYBRID_SPARSE_V1"),
                anyDouble(),
                eq(5.0D),
                anyDouble(),
                anyInt()
        );
    }

    @Test
    void askQuestion_KbNotSparseReady_DoesNotExecuteSparseBranch() {
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sparseEnabled", true);

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("AUTH_001 spring.profiles.active");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(kbDocumentRepository.existsCompletedDocumentWithoutSparseReady(knowledgeBase.getId(), "HYBRID_SPARSE_V1"))
                .thenReturn(true);
        when(queryEmbeddingService.embedQuery("AUTH_001 spring.profiles.active")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Dense chunk", 0, 0.92D)));
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        assertEquals(1, response.getRetrievedChunkCount());
        verify(kbDocumentRepository).existsCompletedDocumentWithoutSparseReady(knowledgeBase.getId(), "HYBRID_SPARSE_V1");
        org.mockito.Mockito.verifyNoInteractions(chunkSparseSearchRepository);
    }

    @Test
    void askQuestion_TopKNull_UsesDefaultFive() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis persistence?");
        request.setTopK(null);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQuery("Redis persistence?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 0, 0.92D)));
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        verify(chunkSearchRepository).searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5);
        org.junit.jupiter.api.Assertions.assertNotNull(response.getAnswer());
        assertEquals(1, response.getRetrievedChunkCount());
    }

    @Test
    void askQuestion_TopKAboveLimit_ClampsToTwenty() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis persistence?");
        request.setTopK(99);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQuery("Redis persistence?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 20))
                .thenReturn(List.of(projection("Chunk content", 0, 0.92D)));
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        verify(chunkSearchRepository).searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 20);
    }

    @Test
    void askQuestion_IncludeSourcesFalse_ReturnsEmptySources() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis persistence?");
        request.setTopK(5);
        request.setIncludeSources(false);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQuery("Redis persistence?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 3, 0.92D)));
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        assertNotNull(response.getSources());
        assertTrue(response.getSources().isEmpty());
    }

    @Test
    void askQuestion_IncludeSourcesFalse_StillReturnsPersistedChatIdentity() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis persistence?");
        request.setTopK(5);
        request.setIncludeSources(false);

        ChatTurnWriteResult writeResult = new ChatTurnWriteResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(ragQaChatSessionService.persistTurn(eq(chatSession), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(writeResult);
        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQuery("Redis persistence?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 3, 0.92D)));
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        assertTrue(response.getSources().isEmpty());
        assertEquals(writeResult.chatSessionId(), response.getChatSessionId());
        assertEquals(writeResult.userMessageId(), response.getUserMessageId());
        assertEquals(writeResult.assistantMessageId(), response.getAssistantMessageId());
        verify(ragQaChatSessionService).persistTurn(eq(chatSession), eq("Redis persistence?"), anyString(), any(), any(), eq(1));
    }

    @Test
    void askQuestion_SimilarityAboveOne_ClampsSourceAndConfidenceToOne() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis persistence?");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQuery("Redis persistence?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 1, 1.25D)));
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        assertEquals(1.0D, response.getConfidence());
        assertEquals(1.0D, response.getSources().getFirst().getSimilarity());
    }

    @Test
    void askQuestion_SimilarityBelowZero_ClampsSourceAndConfidenceToZero() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis persistence?");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQuery("Redis persistence?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 1, -0.25D)));
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        assertEquals(0.0D, response.getConfidence());
        assertEquals(0.0D, response.getSources().getFirst().getSimilarity());
    }

    @Test
    void askQuestion_TopKZero_UsesDefaultFive() {
        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis persistence?");
        request.setTopK(0);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQuery("Redis persistence?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("Chunk content", 1, 0.8D)));
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        assertEquals(1, response.getRetrievedChunkCount());
        verify(chunkSearchRepository).searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5);
    }

    @Test
    void askQuestion_LongSourceContent_TruncatesSnippetByConfiguredLimit() {
        ReflectionTestUtils.setField(knowledgeBaseQueryService, "sourceContentMaxChars", 10);

        KnowledgeBaseQueryAskRequest request = new KnowledgeBaseQueryAskRequest();
        request.setQuestion("Redis persistence?");
        request.setTopK(5);
        request.setIncludeSources(true);

        stubAccessibleKnowledgeBase();
        when(queryEmbeddingService.embedQuery("Redis persistence?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkSearchRepository.searchCompletedChunks(knowledgeBase.getExternalId(), 1L, "[0.1,0.2]", 5))
                .thenReturn(List.of(projection("1234567890ABCDEFGHIJ", 2, 0.91D)));
        when(llmClient.generateStructuredJson(any())).thenReturn(new LlmResponse("{\"answer\":\"A\"}", "default", "qwen"));

        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion("testuser", knowledgeBase.getExternalId(), request);

        assertEquals("1234567890...", response.getSources().getFirst().getContent());
    }

    private void stubAccessibleKnowledgeBase() {
        when(knowledgeBaseAccessService.requireQueryableKnowledgeBase("testuser", knowledgeBase.getExternalId()))
                .thenReturn(knowledgeBase);
    }

    private UsageOperationContext dummyContext() {
        return new UsageOperationContext("rag", "provider", "model", null, "KNOWLEDGE_BASE_QUERY", "kb-1", "op-1", 1L, null, null);
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

    private QueryEmbeddingService.EmbeddingExecutionResult embeddingExecution(String invocationId, String question) {
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
                        Map.of("question", question)
                ),
                com.josh.interviewj.llm.gateway.dto.AiInvocationResult.fromEmbedding(response)
        );
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

    private boolean awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread interrupted while waiting for latch", exception);
        }
    }
}
