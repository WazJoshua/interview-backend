package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.chat.config.ChatProperties;
import com.josh.interviewj.chat.dto.ChatContextWindow;
import com.josh.interviewj.chat.dto.ChatTurnWriteResult;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseAccessService;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.llm.gateway.dto.PromptTemplateRef;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.ragqa.config.ContextAssemblyProperties;
import com.josh.interviewj.ragqa.config.ContextSelectionProperties;
import com.josh.interviewj.ragqa.dto.request.KnowledgeBaseQueryAskRequest;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseQueryResponse;
import com.josh.interviewj.ragqa.model.HybridRetrievalEligibility;
import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.ContextAssemblyResult;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.ContextBlock;
import com.josh.interviewj.ragqa.model.ContextBlockAssemblyResult;
import com.josh.interviewj.ragqa.model.DatabaseRerankConfig;
import com.josh.interviewj.ragqa.model.PreRerankCandidate;
import com.josh.interviewj.ragqa.model.RewriteResult;
import com.josh.interviewj.ragqa.model.RankedChunkCandidate;
import com.josh.interviewj.ragqa.model.RetrievalBranch;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievalPlan;
import com.josh.interviewj.ragqa.model.RetrievedChunk;
import com.josh.interviewj.ragqa.model.FusedChunk;
import com.josh.interviewj.ragqa.model.FusedRetrievalResult;
import com.josh.interviewj.ragqa.repository.ChunkSearchRepository;
import com.josh.interviewj.ragqa.repository.ChunkSparseSearchRepository;
import com.josh.interviewj.usage.model.UsageFamily;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles retrieval-augmented queries over a user's knowledge base.
 */
@Service
public class KnowledgeBaseQueryService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseQueryService.class);

    private static final String PURPOSE_RAG = "rag";
    private static final String PURPOSE_QUERY_REWRITE = "kb_query_rewrite";
    private static final String CHARGE_BUCKET_KB_QUERY = "KB_QUERY_CREDITS";
    private static final String RESOURCE_TYPE_KNOWLEDGE_BASE_QUERY = "KNOWLEDGE_BASE_QUERY";
    private static final String RAG_SYSTEM_PROMPT = "You are a RAG assistant. Answer the question only based on the provided context. " +
            "Return a JSON object with keys answer and confidence. Confidence must be a number between 0 and 1.";

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final QueryUnderstandingService queryUnderstandingService;
    private final RetrievalPlanBuilder retrievalPlanBuilder;
    private final QueryEmbeddingService queryEmbeddingService;
    private final ChunkSearchRepository chunkSearchRepository;
    private final ChunkSparseSearchRepository chunkSparseSearchRepository;
    private final KbDocumentRepository kbDocumentRepository;
    private final ObjectMapper objectMapper;
    private final RetrievalResultFusionService retrievalResultFusionService;
    private final ExecutorService virtualThreadExecutor;
    private final RagQaChatSessionService ragQaChatSessionService;
    private final ChatProperties chatProperties;
    private final PreRerankCandidateBuilder preRerankCandidateBuilder;
    private final ChunkRerankService chunkRerankService;
    private final ContextBlockAssembler contextBlockAssembler;
    private final CoverageAwareContextSelector coverageAwareContextSelector;
    private final DatabaseRerankConfigResolver databaseRerankConfigResolver;
    private final ContextAssemblyProperties contextAssemblyProperties;
    private final ContextSelectionProperties contextSelectionProperties;
    private final AiOperationGateway aiOperationGateway;

    public KnowledgeBaseQueryService(
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            QueryUnderstandingService queryUnderstandingService,
            RetrievalPlanBuilder retrievalPlanBuilder,
            QueryEmbeddingService queryEmbeddingService,
            ChunkSearchRepository chunkSearchRepository,
            ChunkSparseSearchRepository chunkSparseSearchRepository,
            KbDocumentRepository kbDocumentRepository,
            ObjectMapper objectMapper,
            RetrievalResultFusionService retrievalResultFusionService,
            ExecutorService virtualThreadExecutor,
            RagQaChatSessionService ragQaChatSessionService,
            ChatProperties chatProperties,
            AiOperationGateway aiOperationGateway
    ) {
        this(
                knowledgeBaseAccessService,
                queryUnderstandingService,
                retrievalPlanBuilder,
                queryEmbeddingService,
                chunkSearchRepository,
                chunkSparseSearchRepository,
                kbDocumentRepository,
                objectMapper,
                retrievalResultFusionService,
                virtualThreadExecutor,
                ragQaChatSessionService,
                chatProperties,
                defaultRerankPipelineDependencies(objectMapper),
                aiOperationGateway
        );
    }

    public KnowledgeBaseQueryService(
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            QueryUnderstandingService queryUnderstandingService,
            RetrievalPlanBuilder retrievalPlanBuilder,
            QueryEmbeddingService queryEmbeddingService,
            ChunkSearchRepository chunkSearchRepository,
            ChunkSparseSearchRepository chunkSparseSearchRepository,
            KbDocumentRepository kbDocumentRepository,
            ObjectMapper objectMapper,
            RetrievalResultFusionService retrievalResultFusionService,
            ExecutorService virtualThreadExecutor,
            RagQaChatSessionService ragQaChatSessionService,
            ChatProperties chatProperties,
            RerankPipelineDependencies rerankPipelineDependencies,
            AiOperationGateway aiOperationGateway
    ) {
        this(
                knowledgeBaseAccessService,
                queryUnderstandingService,
                retrievalPlanBuilder,
                queryEmbeddingService,
                chunkSearchRepository,
                chunkSparseSearchRepository,
                kbDocumentRepository,
                objectMapper,
                retrievalResultFusionService,
                virtualThreadExecutor,
                ragQaChatSessionService,
                chatProperties,
                rerankPipelineDependencies.preRerankCandidateBuilder(),
                rerankPipelineDependencies.chunkRerankService(),
                rerankPipelineDependencies.contextBlockAssembler(),
                rerankPipelineDependencies.coverageAwareContextSelector(),
                rerankPipelineDependencies.databaseRerankConfigResolver(),
                rerankPipelineDependencies.contextAssemblyProperties(),
                rerankPipelineDependencies.contextSelectionProperties(),
                aiOperationGateway
        );
    }

    @Autowired
    public KnowledgeBaseQueryService(
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            QueryUnderstandingService queryUnderstandingService,
            RetrievalPlanBuilder retrievalPlanBuilder,
            QueryEmbeddingService queryEmbeddingService,
            ChunkSearchRepository chunkSearchRepository,
            ChunkSparseSearchRepository chunkSparseSearchRepository,
            KbDocumentRepository kbDocumentRepository,
            ObjectMapper objectMapper,
            RetrievalResultFusionService retrievalResultFusionService,
            ExecutorService virtualThreadExecutor,
            RagQaChatSessionService ragQaChatSessionService,
            ChatProperties chatProperties,
            PreRerankCandidateBuilder preRerankCandidateBuilder,
            ChunkRerankService chunkRerankService,
            ContextBlockAssembler contextBlockAssembler,
            CoverageAwareContextSelector coverageAwareContextSelector,
            DatabaseRerankConfigResolver databaseRerankConfigResolver,
            ContextAssemblyProperties contextAssemblyProperties,
            ContextSelectionProperties contextSelectionProperties,
            AiOperationGateway aiOperationGateway
    ) {
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.queryUnderstandingService = queryUnderstandingService;
        this.retrievalPlanBuilder = retrievalPlanBuilder;
        this.queryEmbeddingService = queryEmbeddingService;
        this.chunkSearchRepository = chunkSearchRepository;
        this.chunkSparseSearchRepository = chunkSparseSearchRepository;
        this.kbDocumentRepository = kbDocumentRepository;
        this.objectMapper = objectMapper;
        this.retrievalResultFusionService = retrievalResultFusionService;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.ragQaChatSessionService = ragQaChatSessionService;
        this.chatProperties = chatProperties;
        this.preRerankCandidateBuilder = preRerankCandidateBuilder;
        this.chunkRerankService = chunkRerankService;
        this.contextBlockAssembler = contextBlockAssembler;
        this.coverageAwareContextSelector = coverageAwareContextSelector;
        this.databaseRerankConfigResolver = databaseRerankConfigResolver;
        this.contextAssemblyProperties = contextAssemblyProperties;
        this.contextSelectionProperties = contextSelectionProperties;
        this.aiOperationGateway = aiOperationGateway;
    }

    private static ContextAssemblyProperties disabledContextAssemblyProperties() {
        ContextAssemblyProperties properties = new ContextAssemblyProperties();
        properties.setEnabled(false);
        return properties;
    }

    private static ContextSelectionProperties defaultContextSelectionProperties() {
        return new ContextSelectionProperties();
    }

    private static RerankPipelineDependencies defaultRerankPipelineDependencies(ObjectMapper objectMapper) {
        ContextAssemblyProperties contextAssemblyProperties = disabledContextAssemblyProperties();
        ContextSelectionProperties contextSelectionProperties = defaultContextSelectionProperties();
        DatabaseRerankConfigResolver databaseRerankConfigResolver = new DatabaseRerankConfigResolver(
                () -> new com.josh.interviewj.llm.routing.DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of()
                )
        );
        return new RerankPipelineDependencies(
                new PreRerankCandidateBuilder(),
                new ChunkRerankService(null, databaseRerankConfigResolver),
                new ContextBlockAssembler(null, contextAssemblyProperties, objectMapper),
                new CoverageAwareContextSelector(contextSelectionProperties),
                databaseRerankConfigResolver,
                contextAssemblyProperties,
                contextSelectionProperties
        );
    }

    @Value("${app.kb.embedding.default-top-k:5}")
    private int defaultTopK = 5;

    @Value("${app.kb.embedding.max-top-k:20}")
    private int maxTopK = 20;

    @Value("${app.kb.query.source-content-max-chars:500}")
    private int sourceContentMaxChars = 500;

    @Value("${app.kb.query.hybrid.sparse-enabled:false}")
    private boolean sparseEnabled = false;

    @Value("${app.kb.query.hybrid.force-sparse-without-literal-signal:false}")
    private boolean forceSparseWithoutLiteralSignal = false;

    @Value("${app.kb.query.hybrid.sparse-ready-version:HYBRID_SPARSE_V1}")
    private String sparseReadyVersion = "HYBRID_SPARSE_V1";

    @Value("${app.kb.query.hybrid.sparse-content-weight:1.0}")
    private double sparseContentWeight = 1.0D;

    @Value("${app.kb.query.hybrid.sparse-entity-weight-default:3.0}")
    private double sparseEntityWeightDefault = 3.0D;

    @Value("${app.kb.query.hybrid.sparse-entity-weight-single-literal:4.0}")
    private double sparseEntityWeightSingleLiteral = 4.0D;

    @Value("${app.kb.query.hybrid.sparse-entity-weight-multi-literal:5.0}")
    private double sparseEntityWeightMultiLiteral = 5.0D;

    @Value("${app.kb.query.hybrid.sparse-exact-boost:2.0}")
    private double sparseExactBoost = 2.0D;

    @Value("${app.kb.query.hybrid.sparse-timeout-ms:200}")
    private int sparseTimeoutMs = 200;

    /**
     * Retrieves relevant chunks, asks the LLM for a grounded answer, and returns the final response.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @param request question payload
     * @return answer with optional sources and confidence
     */
    public KnowledgeBaseQueryResponse askQuestion(String username, UUID kbExternalId, KnowledgeBaseQueryAskRequest request) {
        long startedAt = System.currentTimeMillis();
        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireQueryableKnowledgeBase(username, kbExternalId);
        RagQaChatSessionService.RagQaChatSessionContext chatSessionContext =
                ragQaChatSessionService.resolveContext(username, knowledgeBase, request.getChatSessionId());
        ChatContextWindow recentWindow = chatSessionContext.recentWindow();

        int topK = normalizeTopK(request.getTopK());
        boolean includeSources = !Boolean.FALSE.equals(request.getIncludeSources());
        logger.info("KB query started: kbExternalId={}, userId={}, topK={}, includeSources={}",
                knowledgeBase.getExternalId(), knowledgeBase.getUserId(), topK, includeSources);

        List<PendingInvocationOutcome> successCandidates = new ArrayList<>();
        List<PendingInvocationOutcome> fallbackCandidates = new ArrayList<>();
        String requestOperationId = "kb-query-" + UUID.randomUUID();
        BusinessOperationContext operationContext = null;
        try {
            operationContext = prepareOperation(knowledgeBase, requestOperationId);
            NormalizedQuery normalizedQuery = queryUnderstandingService.understand(request.getQuestion());
            QueryRewriteService.RewriteExecutionResult rewriteExecutionResult = queryUnderstandingService.queryRewriteService()
                    .rewriteWithExecution(normalizedQuery, operationContext, requestOperationId + ":rewrite");
            RewriteResult rewriteResult = rewriteExecutionResult.rewriteResult();
            if (rewriteExecutionResult.invocationResult() != null) {
                PendingInvocationOutcome rewriteCandidate = new PendingInvocationOutcome(
                        rewriteExecutionResult.invocationContext(),
                        rewriteExecutionResult.invocationResult()
                );
                if (rewriteResult.succeeded()) {
                    successCandidates.add(rewriteCandidate);
                } else {
                    fallbackCandidates.add(rewriteCandidate);
                }
            }

            HybridRetrievalEligibility hybridEligibility = resolveHybridEligibility(knowledgeBase.getId(), normalizedQuery);
            RetrievalPlan retrievalPlan = retrievalPlanBuilder.build(normalizedQuery, rewriteResult, hybridEligibility, topK);
            logQueryUnderstanding(normalizedQuery, rewriteResult);
            logRetrievalPlan(retrievalPlan);

            RetrievalExecutionResult executionResult = executeRetrievalPlan(knowledgeBase, retrievalPlan, requestOperationId, operationContext);
            successCandidates.addAll(executionResult.successUsageCandidates());
            fallbackCandidates.addAll(executionResult.fallbackUsageCandidates());
            List<RetrievedChunk> branchResults = executionResult.chunks();
            if (branchResults.isEmpty()) {
                String noResultReason = classifyNoResultReason(knowledgeBase.getId());
                logger.warn("KB query returned no available content: kbExternalId={}, userId={}, topK={}, reason={}",
                        knowledgeBase.getExternalId(), knowledgeBase.getUserId(), topK, noResultReason);
                throw new BusinessException(ErrorCode.KB_004, "No relevant content found");
            }

            FusedRetrievalResult fusedRetrievalResult = retrievalResultFusionService.fuse(branchResults, retrievalPlan.finalContextTopK());
            if (fusedRetrievalResult.selectedChunks().isEmpty()) {
                String noResultReason = classifyNoResultReason(knowledgeBase.getId());
                logger.warn("KB query returned no available content: kbExternalId={}, userId={}, topK={}, reason={}",
                        knowledgeBase.getExternalId(), knowledgeBase.getUserId(), topK, noResultReason);
                throw new BusinessException(ErrorCode.KB_004, "No relevant content found");
            }

            double averageSimilarity = fusedRetrievalResult.selectedChunks().stream()
                    .mapToDouble(FusedChunk::similarity)
                    .average()
                    .orElse(0D);

            String prompt = buildPromptFromFusedChunks(request.getQuestion(), fusedRetrievalResult.selectedChunks(), recentWindow);
            List<KnowledgeBaseQueryResponse.Source> sourceSnapshot = toSourcesFromFusedChunks(fusedRetrievalResult.selectedChunks());
            List<String> answerFallbackTexts = fusedRetrievalResult.selectedChunks().stream()
                    .map(FusedChunk::content)
                    .toList();
            double responseConfidence = averageSimilarity;
            int retrievedChunkCount = sourceSnapshot.size();
            int availableContextBudget = computeAvailableContextBudget(request.getQuestion(), recentWindow);
            boolean rerankDegraded = false;
            String rerankDegradedReason = "none";
            long rerankStartedAt = 0L;
            long rerankLatencyMs = 0L;
            int rerankCandidateCount = 0;
            int stage1RetainedCount = 0;
            int assembledBlockCount = 0;
            int contextBlocksSelected = 0;
            int mergedBlockCount = 0;
            int overlapFilteredCount = 0;
            int sectionFallbackCount = 0;
            DatabaseRerankConfig rerankConfig = databaseRerankConfigResolver.resolve("kb_query_rerank").orElse(null);
            String invalidRerankReason = databaseRerankConfigResolver.invalidReason("kb_query_rerank").orElse(null);
            boolean rerankConfigured = rerankConfig != null || invalidRerankReason != null;
            int selectedDocumentCount = (int) fusedRetrievalResult.selectedChunks().stream()
                    .map(FusedChunk::documentId)
                    .distinct()
                    .count();
            int selectedSectionCount = 0;
            int finalContextTokens = estimateTextCollectionTokens(answerFallbackTexts);

            if (rerankConfig != null) {
                rerankStartedAt = System.currentTimeMillis();
                try {
                    List<PreRerankCandidate> preRerankCandidates = preRerankCandidateBuilder.build(
                            branchResults,
                            rerankConfig.preRerankCandidateCap()
                    );
                    rerankCandidateCount = preRerankCandidates.size();
                    ChunkRerankService.ChunkRerankResult rerankResult = chunkRerankService.rerank(
                            operationContext,
                            requestOperationId + ":rerank",
                            preRerankCandidates,
                            normalizedQuery,
                            rewriteResult
                    );
                    List<PendingInvocationOutcome> rerankCandidates = rerankResult.invocations().stream()
                            .map(invocation -> new PendingInvocationOutcome(
                                    invocation.invocationContext(),
                                    invocation.invocationResult()
                            ))
                            .toList();
                    rerankLatencyMs = System.currentTimeMillis() - rerankStartedAt;
                    stage1RetainedCount = rerankResult.rankedCandidates().size();

                    if (!rerankResult.degraded() && !rerankResult.rankedCandidates().isEmpty()) {
                        successCandidates.addAll(rerankCandidates);
                        if (!contextAssemblyProperties.isEnabled()) {
                            prompt = buildPromptFromRankedSeeds(request.getQuestion(), rerankResult.rankedCandidates(), recentWindow);
                            sourceSnapshot = toSourcesFromRankedSeeds(rerankResult.rankedCandidates());
                            answerFallbackTexts = rerankResult.rankedCandidates().stream().map(RankedChunkCandidate::content).toList();
                            responseConfidence = averageStage1ScoreForSeeds(rerankResult.rankedCandidates());
                            retrievedChunkCount = sourceSnapshot.size();
                            rerankDegraded = true;
                            rerankDegradedReason = "context_assembly_disabled";
                            selectedDocumentCount = (int) sourceSnapshot.stream().map(KnowledgeBaseQueryResponse.Source::getDocumentId).distinct().count();
                            finalContextTokens = estimateTextCollectionTokens(answerFallbackTexts);
                        } else {
                            try {
                                ContextBlockAssemblyResult blockAssemblyResult = contextBlockAssembler.assemble(rerankResult.rankedCandidates());
                                assembledBlockCount = blockAssemblyResult.blocks().size();
                                mergedBlockCount = blockAssemblyResult.mergedBlockCount();
                                overlapFilteredCount = blockAssemblyResult.overlapFilteredCount();
                                sectionFallbackCount = blockAssemblyResult.sectionFallbackCount();
                                if (blockAssemblyResult.degraded()) {
                                    rerankDegraded = true;
                                    rerankDegradedReason = blockAssemblyResult.degradedReason();
                                }
                                try {
                                    ContextAssemblyResult selectionResult = coverageAwareContextSelector.select(
                                            blockAssemblyResult.blocks(),
                                            availableContextBudget
                                    );
                                    contextBlocksSelected = selectionResult.selectedBlocks().size();
                                    if (selectionResult.selectedBlocks().isEmpty()
                                            && !"none".equals(selectionResult.degradedReason())) {
                                        rerankDegraded = true;
                                        rerankDegradedReason = selectionResult.degradedReason();
                                    }
                                    if (!selectionResult.selectedBlocks().isEmpty()) {
                                        List<RankedChunkCandidate> selectedEvidenceSeeds = collectSelectedEvidenceSeeds(
                                                selectionResult.selectedBlocks(),
                                                rerankResult.rankedCandidates()
                                        );
                                        prompt = buildPromptFromContextBlocks(request.getQuestion(), selectionResult.selectedBlocks(), recentWindow);
                                        sourceSnapshot = toSourcesFromRankedSeeds(selectedEvidenceSeeds);
                                        answerFallbackTexts = selectionResult.selectedBlocks().stream()
                                                .map(ContextBlock::mergedText)
                                                .toList();
                                        responseConfidence = averageStage1ScoreForBlocks(selectionResult.selectedBlocks());
                                        retrievedChunkCount = sourceSnapshot.size();
                                        selectedDocumentCount = selectionResult.selectedDocumentCount();
                                        selectedSectionCount = selectionResult.selectedSectionCount();
                                        finalContextTokens = selectionResult.totalEstimatedTokens();
                                    } else if (!blockAssemblyResult.blocks().isEmpty()) {
                                        List<ContextBlock> degradedBlocks = truncateBlocksByStage1Score(
                                                blockAssemblyResult.blocks(),
                                                availableContextBudget
                                        );
                                        rerankDegraded = true;
                                        rerankDegradedReason = selectionResult.degradedReason();
                                        if (!degradedBlocks.isEmpty()) {
                                            List<RankedChunkCandidate> selectedEvidenceSeeds = collectSelectedEvidenceSeeds(
                                                    degradedBlocks,
                                                    rerankResult.rankedCandidates()
                                            );
                                            prompt = buildPromptFromContextBlocks(request.getQuestion(), degradedBlocks, recentWindow);
                                            sourceSnapshot = toSourcesFromRankedSeeds(selectedEvidenceSeeds);
                                            answerFallbackTexts = degradedBlocks.stream().map(ContextBlock::mergedText).toList();
                                            responseConfidence = averageStage1ScoreForBlocks(degradedBlocks);
                                            retrievedChunkCount = sourceSnapshot.size();
                                            contextBlocksSelected = degradedBlocks.size();
                                            selectedDocumentCount = (int) degradedBlocks.stream().map(ContextBlock::documentId).distinct().count();
                                            selectedSectionCount = (int) degradedBlocks.stream().map(this::sectionKey).distinct().count();
                                            finalContextTokens = estimateBlockTokens(degradedBlocks);
                                        } else {
                                            logger.warn("event=kb_query_context_selection_degraded reason={}", selectionResult.degradedReason());
                                        }
                                    }
                                } catch (RuntimeException selectorException) {
                                    List<ContextBlock> degradedBlocks = truncateBlocksByStage1Score(
                                            blockAssemblyResult.blocks(),
                                            availableContextBudget
                                    );
                                    rerankDegraded = true;
                                    rerankDegradedReason = "context_selection_degraded";
                                    if (!degradedBlocks.isEmpty()) {
                                        List<RankedChunkCandidate> selectedEvidenceSeeds = collectSelectedEvidenceSeeds(
                                                degradedBlocks,
                                                rerankResult.rankedCandidates()
                                        );
                                        prompt = buildPromptFromContextBlocks(request.getQuestion(), degradedBlocks, recentWindow);
                                        sourceSnapshot = toSourcesFromRankedSeeds(selectedEvidenceSeeds);
                                        answerFallbackTexts = degradedBlocks.stream().map(ContextBlock::mergedText).toList();
                                        responseConfidence = averageStage1ScoreForBlocks(degradedBlocks);
                                        retrievedChunkCount = sourceSnapshot.size();
                                        contextBlocksSelected = degradedBlocks.size();
                                        selectedDocumentCount = (int) degradedBlocks.stream().map(ContextBlock::documentId).distinct().count();
                                        selectedSectionCount = (int) degradedBlocks.stream().map(this::sectionKey).distinct().count();
                                        finalContextTokens = estimateBlockTokens(degradedBlocks);
                                    }
                                    logger.warn("event=kb_query_context_selection_degraded reason={}", selectorException.getMessage());
                                }
                            } catch (RuntimeException assemblyException) {
                                prompt = buildPromptFromRankedSeeds(request.getQuestion(), rerankResult.rankedCandidates(), recentWindow);
                                sourceSnapshot = toSourcesFromRankedSeeds(rerankResult.rankedCandidates());
                                answerFallbackTexts = rerankResult.rankedCandidates().stream().map(RankedChunkCandidate::content).toList();
                                responseConfidence = averageStage1ScoreForSeeds(rerankResult.rankedCandidates());
                                retrievedChunkCount = sourceSnapshot.size();
                                rerankDegraded = true;
                                rerankDegradedReason = "context_assembly_degraded";
                                selectedDocumentCount = (int) sourceSnapshot.stream().map(KnowledgeBaseQueryResponse.Source::getDocumentId).distinct().count();
                                finalContextTokens = estimateTextCollectionTokens(answerFallbackTexts);
                                logger.warn("event=kb_query_context_assembly_degraded reason={}", assemblyException.getMessage());
                            }
                        }
                    } else if (rerankResult.degraded()) {
                        fallbackCandidates.addAll(rerankCandidates);
                        rerankDegraded = true;
                        rerankDegradedReason = rerankResult.degradedReason();
                        logger.warn("event=kb_query_rerank_degraded reason={}", rerankResult.degradedReason());
                    }
                } catch (RuntimeException pipelineException) {
                    rerankLatencyMs = System.currentTimeMillis() - rerankStartedAt;
                    rerankDegraded = true;
                    rerankDegradedReason = "pipeline_failed";
                    logger.warn("event=kb_query_rerank_pipeline_failed message={}", pipelineException.getMessage());
                }
            } else if (invalidRerankReason != null) {
                rerankDegraded = true;
                rerankDegradedReason = invalidRerankReason;
                logger.warn("event=kb_query_rerank_degraded reason={}", invalidRerankReason);
            }

            AiInvocationContext ragInvocationContext = new AiInvocationContext(
                    requestOperationId + ":rag",
                    PURPOSE_RAG,
                    UsageFamily.CHAT,
                    CHARGE_BUCKET_KB_QUERY,
                    false,
                    Map.of()
            );
            AiInvocationResult ragInvocationResult = aiOperationGateway.executeInvocation(
                    operationContext,
                    ragInvocationContext,
                    AiInvocationInput.chat(RAG_SYSTEM_PROMPT, prompt, null,
                            new PromptTemplateRef("rag_answer_system_only", Map.of()))
            );
            LlmResponse llmResponse = ragInvocationResult.llmResponse();
            PendingInvocationOutcome ragCandidate = new PendingInvocationOutcome(ragInvocationContext, ragInvocationResult);
            successCandidates.add(ragCandidate);

            List<KnowledgeBaseQueryResponse.Source> sources = includeSources ? sourceSnapshot : List.of();
            String answer = extractAnswer(llmResponse.content(), answerFallbackTexts);
            ChatTurnWriteResult writeResult = ragQaChatSessionService.persistTurn(
                    chatSessionContext.session(),
                    request.getQuestion(),
                    answer,
                    clampConfidence(responseConfidence),
                    sourceSnapshot,
                    retrievedChunkCount
            );

            KnowledgeBaseQueryResponse response = KnowledgeBaseQueryResponse.builder()
                    .answer(answer)
                    .chatSessionId(writeResult.chatSessionId())
                    .userMessageId(writeResult.userMessageId())
                    .assistantMessageId(writeResult.assistantMessageId())
                    .sources(sources)
                    .confidence(clampConfidence(responseConfidence))
                    .retrievedChunkCount(retrievedChunkCount)
                    .processingTime(System.currentTimeMillis() - startedAt)
                    .build();

            submitSuccessCandidates(operationContext, successCandidates);
            submitFailureCandidates(operationContext, fallbackCandidates, InvocationUsageOutcome.FALLBACK_RECOVERED_NON_CHARGEABLE, "kb_query_fallback_recovered");

            logger.info(
                    "event=kb_query_succeeded kbExternalId={} userId={} topK={} hitCount={} includeSources={} processingTimeMs={} kb_sparse_ready={} literal_signal_types={} sparse_candidate_count={} sparse_selected_count={} sparse_only_rescue_count={} cross_branch_mismatch_count={} degraded_reason={} rerank_enabled={} rerank_degraded={} rerank_degraded_reason={} rerank_latency_ms={} rerank_candidate_count={} stage1_retained_count={} context_blocks_assembled={} context_blocks_selected={} context_blocks_merged={} context_blocks_overlap_filtered={} section_fallback_count={} selected_document_count={} selected_section_count={} final_context_tokens={} chat_session_id={} user_message_id={} assistant_message_id={} recent_window_message_count={} recent_window_truncated={}",
                    knowledgeBase.getExternalId(),
                    knowledgeBase.getUserId(),
                    topK,
                    retrievedChunkCount,
                    includeSources,
                    response.getProcessingTime(),
                    hybridEligibility.kbSparseReady(),
                    normalizedQuery.literalSignals().matchedSignalTypes(),
                    fusedRetrievalResult.sparseCandidateCount(),
                    fusedRetrievalResult.sparseSelectedCount(),
                    fusedRetrievalResult.sparseOnlyRescueCount(),
                    fusedRetrievalResult.crossBranchMismatchCount(),
                    rerankDegraded ? rerankDegradedReason : executionResult.degradedReason(),
                    rerankConfigured,
                    rerankDegraded,
                    rerankDegradedReason,
                    rerankLatencyMs,
                    rerankCandidateCount,
                    stage1RetainedCount,
                    assembledBlockCount,
                    contextBlocksSelected,
                    mergedBlockCount,
                    overlapFilteredCount,
                    sectionFallbackCount,
                    selectedDocumentCount,
                    selectedSectionCount,
                    finalContextTokens,
                    response.getChatSessionId(),
                    response.getUserMessageId(),
                    response.getAssistantMessageId(),
                    recentWindow.returnedCount(),
                    recentWindow.truncated()
            );
            return response;
        } catch (RuntimeException exception) {
            List<PendingInvocationOutcome> failedCandidates = new ArrayList<>(successCandidates);
            failedCandidates.addAll(fallbackCandidates);
            if (aiOperationGateway != null && operationContext != null) {
                submitFailureCandidates(operationContext, failedCandidates, InvocationUsageOutcome.FAILED_NON_CHARGEABLE, exception.getMessage());
            }
            throw exception;
        }
    }

    private RetrievalExecutionResult executeRetrievalPlan(
            KnowledgeBase knowledgeBase,
            RetrievalPlan retrievalPlan,
            String requestOperationId,
            BusinessOperationContext operationContext
    ) {
        if (retrievalPlan.branchExecutionMode() == RetrievalPlan.BranchExecutionMode.PARALLEL) {
            List<BranchExecutionHandle> executions = retrievalPlan.branches().stream()
                    .map(branch -> submitBranchExecution(knowledgeBase, branch, requestOperationId, operationContext))
                    .toList();

            List<BranchExecutionResult> results = executions.stream()
                    .map(this::awaitBranchExecution)
                    .toList();
            results.stream()
                    .filter(BranchExecutionResult::timedOut)
                    .forEach(result -> logger.warn(
                            "KB query branch timed out: kbExternalId={}, userId={}, branchVariant={}, branchMode={}, timeoutMs={}",
                            knowledgeBase.getExternalId(),
                            knowledgeBase.getUserId(),
                            result.branch().queryVariant(),
                            result.branch().retrievalMode(),
                            sparseTimeoutMs
                    ));
            List<RetrievedChunk> successfulChunks = results.stream()
                    .filter(result -> result.error() == null)
                    .flatMap(result -> result.chunks().stream())
                    .toList();
            List<PendingInvocationOutcome> successfulUsageCandidates = results.stream()
                    .flatMap(result -> result.successUsageCandidates().stream())
                    .toList();
            List<PendingInvocationOutcome> fallbackUsageCandidates = results.stream()
                    .flatMap(result -> result.fallbackUsageCandidates().stream())
                    .toList();
            List<String> degradedReasons = results.stream()
                    .filter(result -> result.error() != null)
                    .map(BranchExecutionResult::degradedReason)
                    .toList();
            if (!successfulChunks.isEmpty()) {
                return new RetrievalExecutionResult(
                        successfulChunks,
                        degradedReasons.isEmpty() ? "none" : String.join(",", degradedReasons),
                        successfulUsageCandidates,
                        fallbackUsageCandidates
                );
            }
            boolean hasNonFailedBranch = results.stream().anyMatch(result -> result.error() == null);
            if (hasNonFailedBranch) {
                return new RetrievalExecutionResult(
                        List.of(),
                        degradedReasons.isEmpty() ? "none" : String.join(",", degradedReasons),
                        successfulUsageCandidates,
                        fallbackUsageCandidates
                );
            }
            RuntimeException firstError = results.stream()
                    .map(BranchExecutionResult::error)
                    .filter(error -> error != null)
                    .findFirst()
                    .orElse(null);
            if (firstError != null) {
                throw firstError;
            }
            return new RetrievalExecutionResult(
                    List.of(),
                    degradedReasons.isEmpty() ? "none" : String.join(",", degradedReasons),
                    successfulUsageCandidates,
                    fallbackUsageCandidates
            );
        }

        List<BranchRetrievalResult> branchResults = retrievalPlan.branches().stream()
                .map(branch -> executeBranch(knowledgeBase, branch, requestOperationId, operationContext))
                .toList();
        return new RetrievalExecutionResult(
                branchResults.stream().flatMap(result -> result.chunks().stream()).toList(),
                "none",
                branchResults.stream().flatMap(result -> result.successUsageCandidates().stream()).toList(),
                branchResults.stream().flatMap(result -> result.fallbackUsageCandidates().stream()).toList()
        );
    }

    private BranchExecutionResult executeBranchSafely(
            KnowledgeBase knowledgeBase,
            RetrievalBranch branch,
            String requestOperationId,
            BusinessOperationContext operationContext
    ) {
        try {
            BranchRetrievalResult result = executeBranch(knowledgeBase, branch, requestOperationId, operationContext);
            return BranchExecutionResult.success(branch, result);
        } catch (BranchExecutionException exception) {
            logger.warn("KB query branch degraded: kbExternalId={}, userId={}, branchVariant={}, branchMode={}, message={}",
                    knowledgeBase.getExternalId(), knowledgeBase.getUserId(), branch.queryVariant(), branch.retrievalMode(), exception.getMessage());
            return BranchExecutionResult.failure(branch, exception, exception.fallbackUsageCandidates());
        } catch (RuntimeException exception) {
            logger.warn("KB query branch degraded: kbExternalId={}, userId={}, branchVariant={}, branchMode={}, message={}",
                    knowledgeBase.getExternalId(), knowledgeBase.getUserId(), branch.queryVariant(), branch.retrievalMode(), exception.getMessage());
            return BranchExecutionResult.failure(branch, exception, List.of());
        }
    }

    private BranchRetrievalResult executeBranch(
            KnowledgeBase knowledgeBase,
            RetrievalBranch branch,
            String requestOperationId,
            BusinessOperationContext operationContext
    ) {
        if (branch.retrievalMode() == RetrievalMode.SPARSE) {
            double sparseEntityWeight = resolveSparseEntityWeight(branch);
            List<ChunkSparseSearchRepository.SparseChunkProjection> rows = chunkSparseSearchRepository.searchCompletedChunksSparse(
                    knowledgeBase.getExternalId(),
                    knowledgeBase.getUserId(),
                    branch.queryText(),
                    branch.queryText(),
                    toExactTermsPayload(branch.exactBoostTerms()),
                    sparseReadyVersion,
                    sparseContentWeight,
                    sparseEntityWeight,
                    sparseExactBoost,
                    branch.candidateTopK()
            );
            List<RetrievedChunk> retrievedChunks = new java.util.ArrayList<>(rows.size());
            for (int index = 0; index < rows.size(); index++) {
                ChunkSparseSearchRepository.SparseChunkProjection row = rows.get(index);
                retrievedChunks.add(new RetrievedChunk(
                        branch.queryVariant(),
                        branch.retrievalMode(),
                        row.getDocumentExternalId(),
                        row.getDocumentId(),
                        row.getDocumentName(),
                        row.getChunkIndex(),
                        row.getContent(),
                        row.getFinalSparseScore(),
                        index + 1,
                        row.getMetadata()
                ));
            }
            return new BranchRetrievalResult(retrievedChunks, List.of(), List.of());
        }
        QueryEmbeddingService.EmbeddingExecutionResult embeddingExecution = queryEmbeddingService.embedQueryWithUsage(
                operationContext,
                requestOperationId + ":embedding:" + branch.queryVariant().name().toLowerCase(java.util.Locale.ROOT)
                        + ":" + branch.retrievalMode().name().toLowerCase(java.util.Locale.ROOT),
                branch.queryText()
        );
        if (embeddingExecution == null || embeddingExecution.response() == null) {
            throw new IllegalStateException("Query embedding returned no response");
        }
        float[] queryVector = embeddingExecution.response().vector();
        List<ChunkSearchRepository.CompletedChunkProjection> results;
        try {
            results = chunkSearchRepository.searchCompletedChunks(
                    knowledgeBase.getExternalId(),
                    knowledgeBase.getUserId(),
                    toVectorLiteral(queryVector),
                    branch.candidateTopK()
            );
        } catch (RuntimeException exception) {
            throw new BranchExecutionException(
                    exception,
                    embeddingExecution == null || embeddingExecution.invocationResult() == null ? List.of() : List.of(new PendingInvocationOutcome(
                            embeddingExecution.invocationContext(),
                            embeddingExecution.invocationResult()
                    ))
            );
        }
        List<RetrievedChunk> mappedResults = new java.util.ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            ChunkSearchRepository.CompletedChunkProjection row = results.get(index);
            mappedResults.add(new RetrievedChunk(
                    branch.queryVariant(),
                    branch.retrievalMode(),
                    row.getDocumentExternalId(),
                    row.getDocumentId(),
                    row.getDocumentName(),
                    row.getChunkIndex(),
                    row.getContent(),
                    row.getSimilarity(),
                    index + 1,
                        row.getMetadata()
            ));
        }
        return new BranchRetrievalResult(
                mappedResults,
                embeddingExecution == null || embeddingExecution.invocationResult() == null ? List.of() : List.of(new PendingInvocationOutcome(
                        embeddingExecution.invocationContext(),
                        embeddingExecution.invocationResult()
                )),
                List.of()
        );
    }

    private BranchExecutionHandle submitBranchExecution(
            KnowledgeBase knowledgeBase,
            RetrievalBranch branch,
            String requestOperationId,
            BusinessOperationContext operationContext
    ) {
        Future<BranchExecutionResult> future = virtualThreadExecutor.submit(
                () -> executeBranchSafely(knowledgeBase, branch, requestOperationId, operationContext)
        );
        long timeoutDeadlineNanos = branch.retrievalMode() == RetrievalMode.SPARSE
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sparseTimeoutMs)
                : Long.MAX_VALUE;
        return new BranchExecutionHandle(branch, future, timeoutDeadlineNanos);
    }

    private BranchExecutionResult awaitBranchExecution(BranchExecutionHandle execution) {
        try {
            if (execution.branch().retrievalMode() != RetrievalMode.SPARSE) {
                return execution.future().get();
            }
            long remainingTimeoutNanos = execution.timeoutDeadlineNanos() - System.nanoTime();
            if (remainingTimeoutNanos <= 0L) {
                execution.future().cancel(true);
                return BranchExecutionResult.timeout(execution.branch());
            }
            return execution.future().get(remainingTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            execution.future().cancel(true);
            return BranchExecutionResult.timeout(execution.branch());
        } catch (InterruptedException exception) {
            execution.future().cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for KB query branch execution", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof BranchExecutionException branchExecutionException) {
                return BranchExecutionResult.failure(execution.branch(), branchExecutionException, branchExecutionException.fallbackUsageCandidates());
            }
            if (cause instanceof RuntimeException runtimeException) {
                return BranchExecutionResult.failure(execution.branch(), runtimeException, List.of());
            }
            throw new RuntimeException("KB query branch execution failed", cause);
        }
    }

    private double resolveSparseEntityWeight(RetrievalBranch branch) {
        int exactBoostTermCount = branch.exactBoostTerms().size();
        if (exactBoostTermCount > 1) {
            return sparseEntityWeightMultiLiteral;
        }
        if (exactBoostTermCount == 1) {
            return sparseEntityWeightSingleLiteral;
        }
        return sparseEntityWeightDefault;
    }

    private void logQueryUnderstanding(NormalizedQuery normalizedQuery, RewriteResult rewriteResult) {
        logger.info(
                "event=kb_query_understanding raw_query=\"{}\" normalized_query=\"{}\" query_profile=\"{}\" preserved_token_count={} alias_expansion_count={} literal_signal_types={} rewrite_attempted={} rewrite_succeeded={} rewrite_fallback_reason={}",
                queryUnderstandingService.truncateForLog(normalizedQuery.rawText()),
                queryUnderstandingService.truncateForLog(normalizedQuery.normalizedText()),
                normalizedQuery.profile(),
                normalizedQuery.preservedTokens().size(),
                normalizedQuery.aliasExpansions().size(),
                normalizedQuery.literalSignals().matchedSignalTypes(),
                rewriteResult.attempted(),
                rewriteResult.succeeded(),
                rewriteResult.fallbackReason()
        );
    }

    private void logRetrievalPlan(RetrievalPlan retrievalPlan) {
        logger.info(
                "event=kb_query_retrieval_plan retrieval_strategy={} candidate_top_k_per_branch={} final_context_top_k={} branch_execution_mode={}",
                retrievalPlan.strategy(),
                retrievalPlan.candidateTopKPerBranch(),
                retrievalPlan.finalContextTopK(),
                retrievalPlan.branchExecutionMode()
        );
    }

    private HybridRetrievalEligibility resolveHybridEligibility(Long kbId, NormalizedQuery normalizedQuery) {
        if (!sparseEnabled) {
            return HybridRetrievalEligibility.disabled();
        }
        boolean sparseAllowedForQuery = forceSparseWithoutLiteralSignal
                || normalizedQuery.literalSignals().matchedTokenCount() > 0;
        boolean kbSparseReady = !kbDocumentRepository.existsCompletedDocumentWithoutSparseReady(kbId, sparseReadyVersion);
        return HybridRetrievalEligibility.enabled(true, kbSparseReady, sparseAllowedForQuery);
    }

    /**
     * Normalizes requested retrieval depth into configured bounds.
     *
     * @param topK requested top-k value
     * @return bounded top-k
     */
    private int normalizeTopK(Integer topK) {
        if (topK == null || topK < 1) {
            return defaultTopK;
        }
        return Math.min(topK, maxTopK);
    }

    private String classifyNoResultReason(Long kbId) {
        long totalDocuments = kbDocumentRepository.countByKbId(kbId);
        if (totalDocuments == 0) {
            return "no_documents";
        }

        long completedDocuments = kbDocumentRepository.countByKbIdAndStatus(kbId, KbDocumentStatus.COMPLETED);
        if (completedDocuments == 0) {
            return "processing_only";
        }

        long searchableChunks = kbDocumentRepository.countSearchableChunksByKbId(kbId);
        if (searchableChunks == 0) {
            return "completed_without_embeddings";
        }

        return "no_match";
    }

    /**
     * Builds the RAG prompt from the user question and retrieved chunk content.
     *
     * @param question user question
     * @param results retrieved chunks
     * @return prompt passed to the LLM
     */
    String buildPromptFromFusedChunks(String question, List<FusedChunk> results, ChatContextWindow recentWindow) {
        StringBuilder builder = new StringBuilder();
        builder.append("Question:\n").append(question).append("\n\nContext:\n");
        for (FusedChunk row : results) {
            builder.append("[Document: ").append(row.documentName())
                    .append(", Chunk: ").append(row.chunkIndex())
                    .append("]\n")
                    .append(row.content())
                    .append("\n\n");
        }
        builder.append("Return JSON only.");
        return prependRecentConversation(builder.toString(), recentWindow);
    }

    String buildPromptFromRankedSeeds(String question, List<RankedChunkCandidate> seeds, ChatContextWindow recentWindow) {
        StringBuilder builder = new StringBuilder();
        builder.append("Question:\n").append(question).append("\n\nContext:\n");
        for (RankedChunkCandidate seed : seeds) {
            builder.append("[Document: ").append(seed.documentName())
                    .append(", Chunk: ").append(seed.chunkIndex())
                    .append("]\n")
                    .append(seed.content())
                    .append("\n\n");
        }
        builder.append("Return JSON only.");
        return prependRecentConversation(builder.toString(), recentWindow);
    }

    String buildPromptFromContextBlocks(String question, List<ContextBlock> blocks, ChatContextWindow recentWindow) {
        StringBuilder builder = new StringBuilder();
        builder.append("Question:\n").append(question).append("\n\nContext:\n");
        for (ContextBlock block : blocks) {
            builder.append("--- [Source: ").append(block.documentName())
                    .append(", Section: ").append(formatSectionPath(block.sectionPath()))
                    .append("] ---\n")
                    .append(block.mergedText())
                    .append("\n\n");
        }
        builder.append("Return JSON only.");
        return prependRecentConversation(builder.toString(), recentWindow);
    }

    private String prependRecentConversation(String promptBody, ChatContextWindow recentWindow) {
        if (!chatProperties.isRagqaIncludeRecentTurnContext() || recentWindow.messages().isEmpty()) {
            return promptBody;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Recent conversation:\n");
        recentWindow.messages().forEach(message -> builder.append(message.role())
                .append(": ")
                .append(message.content())
                .append("\n"));
        builder.append("\n");
        builder.append(promptBody);
        return builder.toString();
    }

    private String formatSectionPath(List<String> sectionPath) {
        return sectionPath == null || sectionPath.isEmpty()
                ? "Unknown"
                : String.join(" > ", sectionPath);
    }

    /**
     * Extracts the answer field from the structured LLM response with a retrieval fallback.
     *
     * @param llmJson LLM JSON output
     * @param fallbackTexts fallback evidence texts
     * @return answer text
     */
    String extractAnswer(String llmJson, List<String> fallbackTexts) {
        if (llmJson != null && !llmJson.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(llmJson);
                JsonNode answer = root.get("answer");
                if (answer != null && !answer.isNull()) {
                    return answer.asText();
                }
            } catch (Exception ignored) {
            }
        }
        return fallbackTexts == null ? "" : fallbackTexts.stream().findFirst().orElse("");
    }

    private List<KnowledgeBaseQueryResponse.Source> toSourcesFromFusedChunks(List<FusedChunk> chunks) {
        return chunks.stream()
                .map(chunk -> KnowledgeBaseQueryResponse.Source.builder()
                        .documentId(chunk.documentExternalId())
                        .documentName(chunk.documentName())
                        .chunkIndex(chunk.chunkIndex())
                        .content(toSourceContent(chunk.content()))
                        .similarity(clampConfidence(chunk.similarity()))
                        .build())
                .toList();
    }

    private List<KnowledgeBaseQueryResponse.Source> toSourcesFromRankedSeeds(List<RankedChunkCandidate> seeds) {
        return seeds.stream()
                .map(seed -> KnowledgeBaseQueryResponse.Source.builder()
                        .documentId(seed.documentExternalId())
                        .documentName(seed.documentName())
                        .chunkIndex(seed.chunkIndex())
                        .content(toSourceContent(seed.content()))
                        .similarity(clampConfidence(seed.stage1RelevanceScore()))
                        .build())
                .toList();
    }

    private double averageStage1ScoreForSeeds(List<RankedChunkCandidate> seeds) {
        return seeds.stream()
                .mapToDouble(RankedChunkCandidate::stage1RelevanceScore)
                .average()
                .orElse(0D);
    }

    private double averageStage1ScoreForBlocks(List<ContextBlock> blocks) {
        return blocks.stream()
                .mapToDouble(ContextBlock::stage1BestScore)
                .average()
                .orElse(0D);
    }

    private List<RankedChunkCandidate> collectSelectedEvidenceSeeds(
            List<ContextBlock> selectedBlocks,
            List<RankedChunkCandidate> rankedCandidates
    ) {
        Set<String> evidenceKeys = selectedBlocks.stream()
                .flatMap(block -> block.seedChunkIndexes().stream()
                        .map(seedChunkIndex -> block.documentId() + ":" + seedChunkIndex))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return rankedCandidates.stream()
                .filter(candidate -> evidenceKeys.contains(candidate.documentId() + ":" + candidate.chunkIndex()))
                .toList();
    }

    private List<ContextBlock> truncateBlocksByStage1Score(List<ContextBlock> blocks, int availableContextBudget) {
        if (blocks == null || blocks.isEmpty() || availableContextBudget <= 0) {
            return List.of();
        }
        List<ContextBlock> sortedBlocks = blocks.stream()
                .sorted(Comparator.comparingDouble(ContextBlock::stage1BestScore).reversed())
                .toList();
        List<ContextBlock> selected = new ArrayList<>();
        int remainingBudget = availableContextBudget;
        for (ContextBlock block : sortedBlocks) {
            if (block.estimatedTokens() > remainingBudget) {
                continue;
            }
            selected.add(block);
            remainingBudget -= block.estimatedTokens();
        }
        if (!selected.isEmpty()) {
            return selected;
        }
        return List.of();
    }

    private int computeAvailableContextBudget(String question, ChatContextWindow recentWindow) {
        int baseBudget = contextSelectionProperties.getTokenBudget();
        int systemPromptTokens = TokenEstimateUtils.estimate(RAG_SYSTEM_PROMPT);
        int questionTokens = TokenEstimateUtils.estimate(question);
        int recentWindowTokens = recentWindow.messages().stream()
                .mapToInt(message -> TokenEstimateUtils.estimate(message.content()) + TokenEstimateUtils.estimate(message.role().name()) + 2)
                .sum();
        int answerReserve = Math.max(400, baseBudget / 5);
        return Math.max(0, baseBudget - systemPromptTokens - questionTokens - recentWindowTokens - answerReserve);
    }

    private int estimateTextCollectionTokens(List<String> texts) {
        return texts == null ? 0 : texts.stream().mapToInt(TokenEstimateUtils::estimate).sum();
    }

    private int estimateBlockTokens(List<ContextBlock> blocks) {
        return blocks == null ? 0 : blocks.stream().mapToInt(ContextBlock::estimatedTokens).sum();
    }

    private String sectionKey(ContextBlock block) {
        return block.sectionPath().isEmpty()
                ? block.documentId() + ":__none__"
                : String.join(" > ", block.sectionPath());
    }

    public record RerankPipelineDependencies(
            PreRerankCandidateBuilder preRerankCandidateBuilder,
            ChunkRerankService chunkRerankService,
            ContextBlockAssembler contextBlockAssembler,
            CoverageAwareContextSelector coverageAwareContextSelector,
            DatabaseRerankConfigResolver databaseRerankConfigResolver,
            ContextAssemblyProperties contextAssemblyProperties,
            ContextSelectionProperties contextSelectionProperties
    ) {
    }

    /**
     * Converts an embedding vector to PostgreSQL vector literal syntax.
     *
     * @param vector embedding vector
     * @return vector literal
     */
    private String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    private String toExactTermsPayload(List<String> exactTerms) {
        return exactTerms == null || exactTerms.isEmpty()
                ? ""
                : String.join(String.valueOf((char) 31), exactTerms);
    }

    private String toSourceContent(String content) {
        if (content == null || sourceContentMaxChars <= 0 || content.length() <= sourceContentMaxChars) {
            return content;
        }
        return content.substring(0, sourceContentMaxChars) + "...";
    }

    /**
     * Bounds a confidence score to the inclusive 0-1 range.
     *
     * @param value raw confidence value
     * @return bounded confidence
     */
    private double clampConfidence(double value) {
        if (value < 0D) {
            return 0D;
        }
        return Math.min(value, 1D);
    }

    private BusinessOperationContext prepareOperation(KnowledgeBase knowledgeBase, String requestOperationId) {
        return aiOperationGateway.prepareOperation(operationContextForFailure(knowledgeBase, requestOperationId));
    }

    private BusinessOperationContext operationContextForFailure(KnowledgeBase knowledgeBase, String requestOperationId) {
        return new BusinessOperationContext(
                requestOperationId,
                knowledgeBase.getUserId(),
                RESOURCE_TYPE_KNOWLEDGE_BASE_QUERY,
                knowledgeBase.getExternalId().toString(),
                "kb_query",
                List.of(CHARGE_BUCKET_KB_QUERY),
                Map.of()
        );
    }

    private void submitSuccessCandidates(
            BusinessOperationContext operationContext,
            List<PendingInvocationOutcome> candidates
    ) {
        for (PendingInvocationOutcome candidate : candidates) {
            aiOperationGateway.submitInvocationOutcome(
                    operationContext,
                    candidate.invocationContext(),
                    candidate.invocationResult(),
                    ExecutionDisposition.EXECUTED,
                    InvocationUsageOutcome.SUCCESS,
                    null
            );
        }
    }

    private void submitFailureCandidates(
            BusinessOperationContext operationContext,
            List<PendingInvocationOutcome> candidates,
            InvocationUsageOutcome outcome,
            String failureReason
    ) {
        for (PendingInvocationOutcome candidate : candidates) {
            aiOperationGateway.submitInvocationOutcome(
                    operationContext,
                    candidate.invocationContext(),
                    candidate.invocationResult(),
                    ExecutionDisposition.EXECUTED,
                    outcome,
                    failureReason
            );
        }
    }

    private record PendingInvocationOutcome(
            AiInvocationContext invocationContext,
            AiInvocationResult invocationResult
    ) {
    }

    private record BranchExecutionResult(
            RetrievalBranch branch,
            List<RetrievedChunk> chunks,
            RuntimeException error,
            String degradedReason,
            boolean timedOut,
            List<PendingInvocationOutcome> successUsageCandidates,
            List<PendingInvocationOutcome> fallbackUsageCandidates
    ) {
        private static BranchExecutionResult success(RetrievalBranch branch, BranchRetrievalResult result) {
            return new BranchExecutionResult(
                    branch,
                    result.chunks(),
                    null,
                    "none",
                    false,
                    result.successUsageCandidates(),
                    result.fallbackUsageCandidates()
            );
        }

        private static BranchExecutionResult failure(
                RetrievalBranch branch,
                RuntimeException error,
                List<PendingInvocationOutcome> fallbackUsageCandidates
        ) {
            return new BranchExecutionResult(
                    branch,
                    List.of(),
                    error,
                    branch.queryVariant().name() + "_" + branch.retrievalMode().name() + "_EXCEPTION",
                    false,
                    List.of(),
                    fallbackUsageCandidates
            );
        }

        private static BranchExecutionResult timeout(RetrievalBranch branch) {
            return new BranchExecutionResult(
                    branch,
                    List.of(),
                    new RuntimeException("timeout"),
                    branch.queryVariant().name() + "_" + branch.retrievalMode().name() + "_TIMEOUT",
                    true,
                    List.of(),
                    List.of()
            );
        }
    }

    private record BranchExecutionHandle(
            RetrievalBranch branch,
            Future<BranchExecutionResult> future,
            long timeoutDeadlineNanos
    ) {
    }

    private record RetrievalExecutionResult(
            List<RetrievedChunk> chunks,
            String degradedReason,
            List<PendingInvocationOutcome> successUsageCandidates,
            List<PendingInvocationOutcome> fallbackUsageCandidates
    ) {
    }

    private record BranchRetrievalResult(
            List<RetrievedChunk> chunks,
            List<PendingInvocationOutcome> successUsageCandidates,
            List<PendingInvocationOutcome> fallbackUsageCandidates
    ) {
    }

    private static class BranchExecutionException extends RuntimeException {

        private final List<PendingInvocationOutcome> fallbackUsageCandidates;

        private BranchExecutionException(Throwable cause, List<PendingInvocationOutcome> fallbackUsageCandidates) {
            super(cause.getMessage(), cause);
            this.fallbackUsageCandidates = fallbackUsageCandidates == null ? List.of() : List.copyOf(fallbackUsageCandidates);
        }

        private List<PendingInvocationOutcome> fallbackUsageCandidates() {
            return fallbackUsageCandidates;
        }
    }
}
