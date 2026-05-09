package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkCandidate;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkCandidateFactory;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkDocumentContext;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkPersistencePayload;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.StructureAwareChunkingResult;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.StructureAwareChunkingService;
import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.parser.DocumentParserRegistry;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentPreprocessingException;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentPreprocessingPipeline;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInput;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInputAdapter;
import com.josh.interviewj.knowledgebase.preprocessing.review.NormalizedReviewTextRenderer;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewBlockDisposition;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.resume.service.DocumentParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Parses, chunks, embeds, and finalizes knowledge base documents during ingestion.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KbDocumentIngestionService {

    private static final String IDEMPOTENT_KEY_PREFIX = "kb:doc:processed:";
    private static final String STRUCTURED_PARSE_FAILURE_MESSAGE = "文档解析失败，请确认文件可正常打开且内容可读取";
    static final String STAGE_PREPROCESS = "PREPROCESS";
    static final String STAGE_ARTIFACT_PERSIST = "ARTIFACT_PERSIST";
    static final String STAGE_CHUNK_PLAN = "CHUNK_PLAN";
    static final String STAGE_CHUNK_PERSIST = "CHUNK_PERSIST";
    static final String STAGE_EMBED = "EMBED";
    private static final String RESOURCE_TYPE_KB_DOCUMENT = "KB_DOCUMENT";

    private final KbDocumentRepository kbDocumentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseStorageService knowledgeBaseStorageService;
    private final DocumentParserService documentParserService;
    private final DocumentParserRegistry documentParserRegistry;
    private final DocumentPreprocessingPipeline documentPreprocessingPipeline;
    private final FixedSizeChunkingInputAdapter fixedSizeChunkingInputAdapter;
    private final KbDocumentArtifactService kbDocumentArtifactService;
    private final NormalizedReviewTextRenderer normalizedReviewTextRenderer;
    private final KbChunkingService kbChunkingService;
    private final DocumentEmbeddingService documentEmbeddingService;
    private final StringRedisTemplate stringRedisTemplate;
    private final StructureAwareChunkingService structureAwareChunkingService;
    private final ChunkCandidateFactory chunkCandidateFactory;
    private final ChunkPersistenceAssembler chunkPersistenceAssembler;
    private final DocumentPreprocessingProperties preprocessingProperties;
    private final TransactionTemplate transactionTemplate;
    private final KnowledgeBaseReindexCompletionService knowledgeBaseReindexCompletionService;
    private final AiOperationGateway aiOperationGateway;

    @Value("${app.kb.embedding.max-document-chars:500000}")
    private int maxDocumentChars;

    @Value("${app.kb.embedding.max-chunks:800}")
    private int maxChunks;

    @Value("${app.kb.query.hybrid.sparse-ready-version:HYBRID_SPARSE_V1}")
    private String sparseReadyVersion;

    /**
     * Parses a stored file, splits it into chunks, generates embeddings, and persists the result.
     *
     * @param documentId document primary key
     */
    public int ingest(Long documentId) {
        KbDocument document = kbDocumentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_002, "Document not found"));

        // Check if structure-aware chunking is enabled
        ChunkingProperties chunkingProps = preprocessingProperties.getChunking();
        boolean useStructureAware = chunkingProps.isStructureAwareEnabled()
                && documentParserRegistry.supports(document.getFileType(), document.getFileName());

        if (useStructureAware) {
            return ingestStructureAware(document);
        }

        // Fallback to fixed-size chunking
        return ingestFixedSize(document);
    }

    /**
     * Ingest using structure-aware chunking.
     */
    private int ingestStructureAware(KbDocument document) {
        Path filePath = knowledgeBaseStorageService.getFilePath(document.getFileUrl());
        NormalizedDocument normalizedDocument = withStage(
                STAGE_PREPROCESS,
                STRUCTURED_PARSE_FAILURE_MESSAGE,
                () -> preprocessStructuredDocument(filePath, document)
        );
        persistReviewArtifact(document, normalizedDocument);
        StructureAwareChunkingResult result = withStage(
                STAGE_CHUNK_PLAN,
                "文档分块规划失败，请稍后重试",
                () -> structureAwareChunkingService.chunk(normalizedDocument)
        );
        List<ChunkCandidate> candidates = result.candidates();

        if (candidates.isEmpty()) {
            throw new TerminalIngestionException("文档预处理后无可用内容，请确认文件正文可读取");
        }

        if (candidates.size() > maxChunks) {
            throw new TerminalIngestionException("文档分块数量超过限制，请拆分后重新上传");
        }

        // Check total embedding chars
        if (result.totalEmbeddingChars() > maxDocumentChars) {
            throw new TerminalIngestionException("文档过大，请拆分后重新上传");
        }

        List<ChunkInsertPlan> chunkPlans = withStage(
                STAGE_CHUNK_PERSIST,
                "文档分块持久化准备失败，请稍后重试",
                () -> candidates.stream().map(this::toChunkInsertPlan).toList()
        );

        rebuildDocumentChunks(document, chunkPlans);

        return candidates.size();
    }

    /**
     * Ingest using fixed-size chunking (fallback).
     */
    private int ingestFixedSize(KbDocument document) {
        ChunkingPayload payload = withStage(
                STAGE_PREPROCESS,
                STRUCTURED_PARSE_FAILURE_MESSAGE,
                () -> extractChunkingPayload(document)
        );
        String rawText = payload.rawText();

        if (rawText.length() > maxDocumentChars) {
            throw new TerminalIngestionException("文档过大，请拆分后重新上传");
        }

        List<KbChunkingService.ChunkPart> chunks = withStage(
                STAGE_CHUNK_PLAN,
                "文档分块规划失败，请稍后重试",
                () -> kbChunkingService.chunk(rawText)
        );
        if (chunks.size() > maxChunks) {
            throw new TerminalIngestionException("文档分块数量超过限制，请拆分后重新上传");
        }

        List<ChunkInsertPlan> chunkPlans = withStage(
                STAGE_CHUNK_PERSIST,
                "文档分块持久化准备失败，请稍后重试",
                () -> buildFixedSizeChunkPlans(payload, chunks)
        );

        rebuildDocumentChunks(document, chunkPlans);

        return chunks.size();
    }

    private void rebuildDocumentChunks(KbDocument document, List<ChunkInsertPlan> chunkPlans) {
        var knowledgeBase = knowledgeBaseRepository.findById(document.getKbId()).orElse(null);
        Map<Integer, DocumentChunkSnapshot> existingChunksByIndex = documentChunkRepository
                .findByDocumentIdOrderByChunkIndexAsc(document.getId())
                .stream()
                .collect(HashMap::new, (map, chunk) -> map.put(chunk.getChunkIndex(), DocumentChunkSnapshot.from(chunk)), HashMap::putAll);
        Set<Integer> embeddedChunkIndices = new HashSet<>(documentChunkRepository.findEmbeddedChunkIndices(document.getId()));
        Set<Integer> reusableEmbeddedChunkIndices = resolveReusableEmbeddedChunkIndices(existingChunksByIndex, embeddedChunkIndices, chunkPlans);

        withStage(STAGE_CHUNK_PERSIST, "文档分块写入失败，请稍后重试", () -> {
            kbDocumentRepository.updateExpectedChunkCount(document.getId(), chunkPlans.size());
            kbDocumentRepository.clearSparseReady(document.getId());
            documentChunkRepository.deleteByDocumentIdAndChunkIndexGreaterThanEqual(document.getId(), chunkPlans.size());
            for (ChunkInsertPlan chunkPlan : chunkPlans) {
                documentChunkRepository.upsertChunk(
                        document.getId(),
                        document.getKbId(),
                        chunkPlan.content(),
                        chunkPlan.chunkIndex(),
                        chunkPlan.startPosition(),
                        chunkPlan.endPosition(),
                        chunkPlan.tokenCount(),
                        chunkPlan.persistencePayload().metadataJson(),
                        chunkPlan.persistencePayload().sparseContentText(),
                        chunkPlan.persistencePayload().sparseEntitiesText(),
                        toExactTermsPayload(chunkPlan.persistencePayload().sparseExactTerms())
                );
            }
            return null;
        });

        withStage(STAGE_EMBED, "知识库处理服务暂时不可用，请稍后重试", () -> {
            String documentResourceExternalId = document.getExternalId() == null
                    ? String.valueOf(document.getId())
                    : document.getExternalId().toString();
            BusinessOperationContext operationContext = null;
            if (knowledgeBase != null && knowledgeBase.getUserId() != null) {
                String businessOperationId = "kb-document-embedding-" + documentResourceExternalId + "-" + UUID.randomUUID();
                operationContext = aiOperationGateway.prepareOperation(new BusinessOperationContext(
                        businessOperationId,
                        knowledgeBase.getUserId(),
                        RESOURCE_TYPE_KB_DOCUMENT,
                        documentResourceExternalId,
                        "kb_document_embedding",
                        List.of("KB_INGESTION_CREDITS"),
                        Map.of()
                ));
            }
            try {
                for (ChunkInsertPlan chunkPlan : chunkPlans) {
                    if (reusableEmbeddedChunkIndices.contains(chunkPlan.chunkIndex())) {
                        int embeddedCount = documentChunkRepository.countEmbeddedChunks(document.getId());
                        kbDocumentRepository.updateEmbeddingProgress(document.getId(), embeddedCount, KbDocumentStatus.PROCESSING);
                        continue;
                    }
                    DocumentEmbeddingService.EmbeddingExecutionResult embeddingExecution = embedChunk(
                            operationContext,
                            documentResourceExternalId,
                            chunkPlan
                    );
                    EmbeddingResponse embeddingResponse = embeddingExecution.response();
                    documentChunkRepository.updateEmbeddingIfNull(
                            document.getId(),
                            chunkPlan.chunkIndex(),
                            toVectorLiteral(embeddingResponse.vector())
                    );
                    if (operationContext != null) {
                        aiOperationGateway.submitInvocationOutcome(
                                operationContext,
                                embeddingExecution.invocationContext(),
                                embeddingExecution.invocationResult(),
                                ExecutionDisposition.EXECUTED,
                                InvocationUsageOutcome.SUCCESS,
                                null
                        );
                    }
                    int embeddedCount = documentChunkRepository.countEmbeddedChunks(document.getId());
                    kbDocumentRepository.updateEmbeddingProgress(document.getId(), embeddedCount, KbDocumentStatus.PROCESSING);
                }
                kbDocumentRepository.markSparseReady(document.getId(), sparseReadyVersion, LocalDateTime.now());
            } catch (RuntimeException exception) {
                throw exception;
            }
            return null;
        });
    }

    private Set<Integer> resolveReusableEmbeddedChunkIndices(
            Map<Integer, DocumentChunkSnapshot> existingChunksByIndex,
            Set<Integer> embeddedChunkIndices,
            List<ChunkInsertPlan> chunkPlans
    ) {
        Set<Integer> reusableChunkIndices = new HashSet<>();
        for (ChunkInsertPlan chunkPlan : chunkPlans) {
            int chunkIndex = chunkPlan.chunkIndex();
            if (!embeddedChunkIndices.contains(chunkIndex)) {
                continue;
            }
            DocumentChunkSnapshot existingChunk = existingChunksByIndex.get(chunkIndex);
            if (existingChunk == null) {
                continue;
            }
            if (existingChunk.matches(chunkPlan)) {
                reusableChunkIndices.add(chunkIndex);
            }
        }
        return reusableChunkIndices;
    }

    private ChunkingPayload extractChunkingPayload(KbDocument document) {
        Path filePath = knowledgeBaseStorageService.getFilePath(document.getFileUrl());
        if (documentParserRegistry.supports(document.getFileType(), document.getFileName())) {
            NormalizedDocument normalizedDocument = preprocessStructuredDocument(filePath, document);
            persistReviewArtifact(document, normalizedDocument);
            FixedSizeChunkingInput chunkingInput = fixedSizeChunkingInputAdapter.adapt(normalizedDocument);
            if (chunkingInput.normalizedTextForChunking().isBlank()) {
                throw new TerminalIngestionException("文档预处理后无可用内容，请确认文件正文可读取");
            }
            return new ChunkingPayload(
                    chunkingInput.normalizedTextForChunking(),
                    chunkingInput.retainedSegments(),
                    ChunkDocumentContext.builder()
                            .sourceType(normalizedDocument.sourceType() == null ? "" : normalizedDocument.sourceType().name())
                            .documentTitle(normalizedDocument.title())
                            .fileName(normalizedDocument.fileName())
                            .preprocessingVersion(String.valueOf(normalizedDocument.metadata().getOrDefault("preprocessingVersion", "v1")))
                            .build()
            );
        }
        return new ChunkingPayload(
                extractRawText(filePath, document.getFileType()),
                List.of(),
                ChunkDocumentContext.builder()
                        .sourceType("RAW_TEXT")
                        .fileName(document.getFileName())
                        .preprocessingVersion("raw")
                        .build()
        );
    }

    private NormalizedDocument preprocessStructuredDocument(Path filePath, KbDocument document) {
        try {
            return documentPreprocessingPipeline.preprocess(
                    filePath,
                    document.getFileType(),
                    document.getFileName()
            );
        } catch (DocumentPreprocessingException ex) {
            throw new TerminalIngestionException(STRUCTURED_PARSE_FAILURE_MESSAGE);
        }
    }

    /**
     * Runs ingestion, marks the document completed, and only writes the idempotent key after commit.
     *
     * @param documentId document primary key
     * @param outboxId outbox primary key
     * @param idempotentCacheTtlMs idempotent key ttl in milliseconds
     */
    public void ingestAndFinalize(Long documentId, Long outboxId, long idempotentCacheTtlMs) {
        int completedChunkCount = ingest(documentId);
        transactionTemplate.executeWithoutResult(status -> {
            int updated = kbDocumentRepository.markCompleted(
                    documentId,
                    KbDocumentStatus.PROCESSING,
                    KbDocumentStatus.COMPLETED,
                    java.time.LocalDateTime.now()
            );
            if (updated > 0) {
                KbDocument document = kbDocumentRepository.findById(documentId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.KB_002, "Document not found"));
                knowledgeBaseRepository.incrementTotalChunks(document.getKbId(), completedChunkCount);
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    stringRedisTemplate.opsForValue().set(
                            IDEMPOTENT_KEY_PREFIX + outboxId,
                            "1",
                            idempotentCacheTtlMs,
                            java.util.concurrent.TimeUnit.MILLISECONDS
                    );
                    KbDocument completedDocument = kbDocumentRepository.findById(documentId).orElse(null);
                    if (completedDocument != null) {
                        try {
                            knowledgeBaseReindexCompletionService.completeIfIdle(completedDocument.getKbId());
                        } catch (RuntimeException ex) {
                            log.warn("KB reindex completion failed after commit: kbId={}, documentId={}, error={}",
                                    completedDocument.getKbId(), documentId, ex.getMessage());
                        }
                    }
                }
            });
        });
    }

    private String extractRawText(Path filePath, String fileType) {
        try {
            return documentParserService.extractText(
                    filePath,
                    fileType
            );
        } catch (TerminalIngestionException ex) {
            throw ex;
        } catch (BusinessException ex) {
            if (ErrorCode.RESUME_003.equals(ex.getErrorCode()) || ErrorCode.FILE_001.equals(ex.getErrorCode())) {
                throw new TerminalIngestionException(STRUCTURED_PARSE_FAILURE_MESSAGE);
            }
            throw new RuntimeException("文档处理失败，请稍后重试", ex);
        } catch (Exception ex) {
            throw new TerminalIngestionException(STRUCTURED_PARSE_FAILURE_MESSAGE);
        }
    }

    private boolean overlaps(int segmentStart, int segmentEnd, int chunkStart, int chunkEnd) {
        return segmentStart < chunkEnd && chunkStart < segmentEnd;
    }

    private List<ChunkInsertPlan> buildFixedSizeChunkPlans(
            ChunkingPayload payload,
            List<KbChunkingService.ChunkPart> chunks
    ) {
        return chunks.stream()
                .map(chunk -> {
                    List<FixedSizeChunkingInput.RetainedSegment> overlappingSegments = payload.retainedSegments().stream()
                            .filter(segment -> overlaps(segment.startOffset(), segment.endOffset(), chunk.startPosition(), chunk.endPosition()))
                            .toList();
                    ChunkCandidate candidate = chunkCandidateFactory.createFixedSizeCandidate(
                            chunk,
                            payload.documentContext(),
                            overlappingSegments
                    );
                    return toChunkInsertPlan(candidate);
                })
                .toList();
    }

    private ChunkInsertPlan toChunkInsertPlan(ChunkCandidate candidate) {
        ChunkPersistencePayload persistencePayload = chunkPersistenceAssembler.assemble(candidate);
        return new ChunkInsertPlan(
                candidate.chunkIndex(),
                candidate.displayText(),
                candidate.embeddingText(),
                candidate.derivationContext().startPosition(),
                candidate.derivationContext().endPosition(),
                candidate.tokenCountEstimate(),
                persistencePayload
        );
    }

    private void persistReviewArtifact(KbDocument document, NormalizedDocument normalizedDocument) {
        withStage(STAGE_ARTIFACT_PERSIST, "文档预处理结果保存失败，请稍后重试", () -> {
            kbDocumentArtifactService.upsertNormalizedReviewText(
                    document.getId(),
                    normalizedReviewTextRenderer.render(normalizedDocument.reviewProjection()),
                    buildReviewArtifactMetadata(normalizedDocument)
            );
            return null;
        });
    }

    private <T> T withStage(String stage, String safeSummary, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (TerminalIngestionException | KbIngestionStageException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new KbIngestionStageException(stage, safeSummary, ex);
        }
    }

    private Map<String, Object> buildReviewArtifactMetadata(NormalizedDocument normalizedDocument) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceType", normalizedDocument.sourceType() == null ? null : normalizedDocument.sourceType().name());
        metadata.put("preprocessingVersion", normalizedDocument.metadata().getOrDefault("preprocessingVersion", "v1"));
        metadata.put("includedBlockCount", normalizedDocument.reviewProjection().blocks().stream()
                .filter(block -> block.disposition() == ReviewBlockDisposition.INDEX)
                .count());
        metadata.put("reviewOnlyBlockCount", normalizedDocument.reviewProjection().blocks().stream()
                .filter(block -> block.disposition() == ReviewBlockDisposition.REVIEW_ONLY)
                .count());
        metadata.put("warningCodes", normalizedDocument.warnings().stream()
                .map(warning -> warning.code())
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList());
        metadata.put("fitForMainIndex", normalizedDocument.qualitySummary().fitForMainIndex());
        metadata.put("originalBlockCount", normalizedDocument.qualitySummary().originalBlockCount());
        metadata.put("normalizedBlockCount", normalizedDocument.qualitySummary().normalizedBlockCount());
        return metadata;
    }

    private DocumentEmbeddingService.EmbeddingExecutionResult embedChunk(
            BusinessOperationContext operationContext,
            String documentResourceExternalId,
            ChunkInsertPlan chunkPlan
    ) {
        if (operationContext == null || operationContext.userId() == null || operationContext.userId() <= 0L) {
            throw new IllegalStateException("Document embedding requires a valid user-scoped operation context");
        }
        String invocationId = operationContext.businessOperationId() + ":chunk-" + chunkPlan.chunkIndex();
        try {
            DocumentEmbeddingService.EmbeddingExecutionResult embeddingExecution = documentEmbeddingService.embedDocumentWithUsage(
                    operationContext,
                    invocationId,
                    chunkPlan.embeddingText()
            );
            if (embeddingExecution == null || embeddingExecution.response() == null) {
                throw new IllegalStateException("Document embedding returned no response");
            }
            return embeddingExecution;
        } catch (BusinessException ex) {
            if (ErrorCode.LLM_001.equals(ex.getErrorCode())) {
                throw new RuntimeException("知识库处理服务暂时不可用，请稍后重试", ex);
            }
            throw new RuntimeException("文档处理失败，请稍后重试", ex);
        } catch (Exception ex) {
            throw new RuntimeException("文档处理失败，请稍后重试", ex);
        }
    }

    /**
     * Converts an embedding vector into PostgreSQL vector literal syntax.
     *
     * @param vector embedding vector
     * @return vector literal string
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

    public static class TerminalIngestionException extends RuntimeException {

        /**
         * Creates an exception for ingestion failures that should not be retried.
         *
         * @param message safe failure reason
         */
        public TerminalIngestionException(String message) {
            super(message);
        }
    }

    private record ChunkingPayload(
            String rawText,
            List<FixedSizeChunkingInput.RetainedSegment> retainedSegments,
            ChunkDocumentContext documentContext
    ) {
    }

    private record ChunkInsertPlan(
            int chunkIndex,
            String content,
            String embeddingText,
            Integer startPosition,
            Integer endPosition,
            int tokenCount,
            ChunkPersistencePayload persistencePayload
    ) {
    }

    private record DocumentChunkSnapshot(
            String content,
            Integer startPosition,
            Integer endPosition,
            Integer tokenCount
    ) {
        private static DocumentChunkSnapshot from(com.josh.interviewj.knowledgebase.model.DocumentChunk chunk) {
            return new DocumentChunkSnapshot(
                    chunk.getContent(),
                    chunk.getStartPosition(),
                    chunk.getEndPosition(),
                    chunk.getTokenCount()
            );
        }

        private boolean matches(ChunkInsertPlan chunkPlan) {
            return Objects.equals(content, chunkPlan.content())
                    && Objects.equals(startPosition, chunkPlan.startPosition())
                    && Objects.equals(endPosition, chunkPlan.endPosition())
                    && Objects.equals(tokenCount, chunkPlan.tokenCount());
        }
    }
}
