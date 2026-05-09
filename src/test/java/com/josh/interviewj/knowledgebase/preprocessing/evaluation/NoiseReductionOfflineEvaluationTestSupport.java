package com.josh.interviewj.knowledgebase.preprocessing.evaluation;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalBlockRuleEngine;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalProtectionRules;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalScoreRules;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalStrongDropRules;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDispositionReasonCode;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarning;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarningCategory;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.parser.DocxDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.parser.DocumentParserRegistry;
import com.josh.interviewj.knowledgebase.preprocessing.parser.MarkdownDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.parser.PdfDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DefaultDocumentPreprocessingPipeline;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentCleaningStep;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInput;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInputAdapter;
import com.josh.interviewj.knowledgebase.preprocessing.steps.BuildQualitySummaryStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.DeduplicateBlocksStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.DropLowSignalBlocksStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.NormalizeCharactersStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.NormalizeDocumentMetadataStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.NormalizeWhitespaceStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemoveEmptyBlocksStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemovePageNumbersStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemoveRepeatedHeadersFootersStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RemoveTocFragmentsStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.RepairBrokenLinesAndParagraphsStep;
import com.josh.interviewj.knowledgebase.preprocessing.steps.SplitPayloadHeavyBlocksStep;
import com.josh.interviewj.knowledgebase.service.KbChunkingService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class NoiseReductionOfflineEvaluationTestSupport {

    private static final int REPORT_TOP_K = 3;
    private static final int EVALUATION_CHUNK_CHARS = 600;
    private static final double SECONDARY_INDEX_CANDIDATE_RATIO_THRESHOLD = 0.6D;
    private static final DocumentPreprocessingProperties PROPERTIES = new DocumentPreprocessingProperties();
    private static final DefaultDocumentPreprocessingPipeline PIPELINE = new DefaultDocumentPreprocessingPipeline(
            new DocumentParserRegistry(List.of(new PdfDocumentParser(), new DocxDocumentParser(), new MarkdownDocumentParser())),
            PROPERTIES,
            createSteps()
    );
    private static final FixedSizeChunkingInputAdapter ADAPTER = new FixedSizeChunkingInputAdapter();
    private NoiseReductionOfflineEvaluationTestSupport() {
    }

    public static EvaluationRunResult runEvaluation(List<NoiseReductionEvaluationCase> cases) {
        List<CaseResult> caseResults = cases.stream()
                .map(NoiseReductionOfflineEvaluationTestSupport::evaluateCase)
                .toList();
        Path reportPath = NoiseReductionReportWriter.writeReport(caseResults);
        return new EvaluationRunResult(reportPath, caseResults);
    }

    public record EvaluationRunResult(
            java.nio.file.Path reportPath,
            List<CaseResult> caseResults
    ) {
    }

    public record EvaluatedChunk(
            int chunkIndex,
            String content,
            List<String> retrievalDispositionReasonCodes,
            boolean secondaryIndexCandidate,
            boolean hasProtectedAnchor,
            double softDeindexBlockRatio,
            double score
    ) {
        public EvaluatedChunk withScore(double nextScore) {
            return new EvaluatedChunk(
                    chunkIndex,
                    content,
                    retrievalDispositionReasonCodes,
                    secondaryIndexCandidate,
                    hasProtectedAnchor,
                    softDeindexBlockRatio,
                    nextScore
            );
        }
    }

    public record CaseResult(
            String caseId,
            String fixture,
            String query,
            List<Integer> baselineTopChunkIds,
            List<Integer> shadowTopChunkIds,
            boolean expectedAnchorHitBaseline,
            boolean expectedAnchorHitShadow,
            double payloadHitRatioBaseline,
            double payloadHitRatioShadow,
            boolean protectedAnchorRegression,
            double secondaryIndexCandidateRatio,
            List<Integer> secondaryIndexCandidateChunkIds
    ) {
    }

    private static CaseResult evaluateCase(NoiseReductionEvaluationCase evaluationCase) {
        try {
            MaterializedFixture fixture = materializeFixture(evaluationCase);
            NormalizedDocument normalizedDocument = PIPELINE.preprocess(fixture.path(), fixture.fileType(), fixture.fileName());
            FixedSizeChunkingInput baselineInput = adaptAllBlocks(normalizedDocument);
            FixedSizeChunkingInput shadowInput = ADAPTER.adapt(normalizedDocument);
            List<KbChunkingService.ChunkPart> baselineChunks = chunkForEvaluation(baselineInput.normalizedTextForChunking());
            List<EvaluatedChunk> baselineEvaluatedChunks = baselineChunks.stream()
                    .map(chunk -> toEvaluatedChunk(baselineInput, chunk))
                    .toList();
            List<KbChunkingService.ChunkPart> shadowChunks = chunkForEvaluation(shadowInput.normalizedTextForChunking());
            List<EvaluatedChunk> shadowEvaluatedChunks = shadowChunks.stream()
                    .map(chunk -> toEvaluatedChunk(shadowInput, chunk))
                    .toList();

            List<EvaluatedChunk> baselineRanking = ShadowChunkRanker.rank(evaluationCase.query(), baselineEvaluatedChunks, false)
                    .stream()
                    .limit(REPORT_TOP_K)
                    .toList();
            List<EvaluatedChunk> shadowRanking = ShadowChunkRanker.rank(evaluationCase.query(), shadowEvaluatedChunks, true)
                    .stream()
                    .limit(REPORT_TOP_K)
                    .toList();

            boolean expectedAnchorHitBaseline = containsExpectedAnchor(baselineRanking, evaluationCase.expectedAnchors());
            boolean expectedAnchorHitShadow = containsExpectedAnchor(shadowRanking, evaluationCase.expectedAnchors());
            double payloadHitRatioBaseline = payloadHitRatio(baselineRanking, evaluationCase);
            double payloadHitRatioShadow = payloadHitRatio(shadowRanking, evaluationCase);
            List<Integer> secondaryIndexCandidateChunkIds = shadowEvaluatedChunks.stream()
                    .filter(EvaluatedChunk::secondaryIndexCandidate)
                    .map(EvaluatedChunk::chunkIndex)
                    .toList();

            return new CaseResult(
                    evaluationCase.id(),
                    evaluationCase.fixture(),
                    evaluationCase.query(),
                    baselineRanking.stream().map(EvaluatedChunk::chunkIndex).toList(),
                    shadowRanking.stream().map(EvaluatedChunk::chunkIndex).toList(),
                    expectedAnchorHitBaseline,
                    expectedAnchorHitShadow,
                    payloadHitRatioBaseline,
                    payloadHitRatioShadow,
                    expectedAnchorHitBaseline && !expectedAnchorHitShadow,
                    shadowEvaluatedChunks.isEmpty() ? 0.0D : secondaryIndexCandidateChunkIds.size() / (double) shadowEvaluatedChunks.size(),
                    secondaryIndexCandidateChunkIds
            );
        } catch (IOException ex) {
            throw new RuntimeException("Failed to evaluate case " + evaluationCase.id(), ex);
        }
    }

    private static EvaluatedChunk toEvaluatedChunk(FixedSizeChunkingInput chunkingInput, KbChunkingService.ChunkPart chunk) {
        List<FixedSizeChunkingInput.RetainedSegment> overlappingSegments = chunkingInput.retainedSegments().stream()
                .filter(segment -> segment.startOffset() < chunk.endPosition() && chunk.startPosition() < segment.endOffset())
                .toList();
        double softDeindexBlockRatio = calculateSoftDeindexBlockRatio(overlappingSegments);
        boolean hasProtectedAnchor = overlappingSegments.stream()
                .anyMatch(segment -> segment.retrievalDisposition() == RetrievalDisposition.PROTECT);
        boolean hasExplanatoryBodyEvidence = overlappingSegments.stream()
                .anyMatch(segment -> segment.retrievalDisposition() == RetrievalDisposition.KEEP);
        boolean secondaryIndexCandidate = softDeindexBlockRatio >= SECONDARY_INDEX_CANDIDATE_RATIO_THRESHOLD
                && !hasProtectedAnchor
                && !hasExplanatoryBodyEvidence;
        List<String> reasonCodes = overlappingSegments.stream()
                .flatMap(segment -> segment.retrievalDispositionReasonCodes().stream())
                .map(Enum::name)
                .distinct()
                .sorted()
                .toList();
        return new EvaluatedChunk(
                chunk.chunkIndex(),
                chunk.content(),
                reasonCodes,
                secondaryIndexCandidate,
                hasProtectedAnchor,
                softDeindexBlockRatio,
                0.0D
        );
    }

    private static List<KbChunkingService.ChunkPart> chunkForEvaluation(String text) {
        String safeText = text == null ? "" : text.strip();
        if (safeText.isEmpty()) {
            return List.of();
        }
        List<KbChunkingService.ChunkPart> chunks = new java.util.ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < safeText.length()) {
            int end = Math.min(start + EVALUATION_CHUNK_CHARS, safeText.length());
            String content = safeText.substring(start, end);
            chunks.add(new KbChunkingService.ChunkPart(index++, content, start, end, content.length()));
            start = end;
        }
        return chunks;
    }

    private static FixedSizeChunkingInput adaptAllBlocks(NormalizedDocument normalizedDocument) {
        StringBuilder builder = new StringBuilder();
        List<FixedSizeChunkingInput.RetainedSegment> segments = new ArrayList<>();
        List<String> documentWarnings = normalizedDocument.warnings().stream()
                .map(DocumentWarning::code)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        boolean hasStructuralWarning = normalizedDocument.warnings().stream()
                .anyMatch(warning -> warning.category() == DocumentWarningCategory.STRUCTURAL);

        for (NormalizedBlock block : normalizedDocument.blocks()) {
            String text = block.text() == null ? "" : block.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            int startOffset = builder.length();
            if (builder.length() > 0) {
                builder.append("\n\n");
                startOffset = builder.length();
            }
            builder.append(text);
            int endOffset = builder.length();

            RetrievalDisposition retrievalDisposition = resolveRetrievalDisposition(block);
            List<RetrievalDispositionReasonCode> reasonCodes = resolveReasonCodes(block);
            List<String> qualityFlags = new ArrayList<>();
            if (retrievalDisposition == RetrievalDisposition.SOFT_DEINDEX) {
                qualityFlags.add("HAS_SOFT_DEINDEX_BLOCK");
            }
            if (retrievalDisposition == RetrievalDisposition.PROTECT) {
                qualityFlags.add("HAS_PROTECTED_ANCHOR");
            }
            if (hasStructuralWarning) {
                qualityFlags.add("HAS_STRUCTURAL_WARNING");
            }

            segments.add(FixedSizeChunkingInput.RetainedSegment.builder()
                    .blockOrder(block.order())
                    .startOffset(startOffset)
                    .endOffset(endOffset)
                    .pageNumber(block.pageNumber())
                    .blockType(block.type())
                    .retrievalDisposition(retrievalDisposition)
                    .retrievalDispositionReasonCodes(reasonCodes)
                    .retrievalDispositionEvidence(List.of())
                    .qualityFlags(qualityFlags.stream().sorted().toList())
                    .preprocessingWarnings(documentWarnings)
                    .build());
        }

        return FixedSizeChunkingInput.builder()
                .normalizedTextForChunking(builder.toString())
                .retainedSegments(segments)
                .build();
    }

    private static double calculateSoftDeindexBlockRatio(List<FixedSizeChunkingInput.RetainedSegment> segments) {
        if (segments.isEmpty()) {
            return 0.0D;
        }
        long softDeindexCount = segments.stream()
                .filter(segment -> segment.retrievalDisposition() == RetrievalDisposition.SOFT_DEINDEX)
                .count();
        return softDeindexCount / (double) segments.size();
    }

    private static RetrievalDisposition resolveRetrievalDisposition(NormalizedBlock block) {
        Object rawDisposition = block.metadata().get("retrievalDisposition");
        if (rawDisposition instanceof String dispositionValue && !dispositionValue.isBlank()) {
            try {
                return RetrievalDisposition.valueOf(dispositionValue.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return RetrievalDisposition.KEEP;
            }
        }
        return RetrievalDisposition.KEEP;
    }

    private static List<RetrievalDispositionReasonCode> resolveReasonCodes(NormalizedBlock block) {
        Object rawReasonCodes = block.metadata().get("retrievalDispositionReasonCodes");
        if (!(rawReasonCodes instanceof List<?> values)) {
            return List.of();
        }
        List<RetrievalDispositionReasonCode> parsed = new ArrayList<>();
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            try {
                parsed.add(RetrievalDispositionReasonCode.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid reason codes in offline baseline simulation.
            }
        }
        return parsed.stream().distinct().toList();
    }

    private static boolean containsExpectedAnchor(List<EvaluatedChunk> rankedChunks, List<String> expectedAnchors) {
        if (expectedAnchors.isEmpty()) {
            return true;
        }
        String combined = rankedChunks.stream()
                .map(EvaluatedChunk::content)
                .collect(Collectors.joining("\n"));
        return expectedAnchors.stream().allMatch(combined::contains);
    }

    private static double payloadHitRatio(List<EvaluatedChunk> rankedChunks, NoiseReductionEvaluationCase evaluationCase) {
        if (rankedChunks.isEmpty()) {
            return 0.0D;
        }
        long payloadHits = rankedChunks.stream()
                .filter(chunk -> matchesPayloadAvoidance(chunk, evaluationCase))
                .count();
        return payloadHits / (double) rankedChunks.size();
    }

    private static boolean matchesPayloadAvoidance(EvaluatedChunk chunk, NoiseReductionEvaluationCase evaluationCase) {
        if (!chunk.secondaryIndexCandidate()) {
            return false;
        }
        boolean hasAvoidMatchers = !evaluationCase.avoidPatterns().isEmpty() || !evaluationCase.avoidReasonCodes().isEmpty();
        if (!hasAvoidMatchers) {
            return true;
        }
        boolean matchesPattern = evaluationCase.avoidPatterns().stream().anyMatch(chunk.content()::contains);
        boolean matchesReasonCode = chunk.retrievalDispositionReasonCodes().stream()
                .anyMatch(evaluationCase.avoidReasonCodes()::contains);
        return matchesPattern || matchesReasonCode;
    }

    private static MaterializedFixture materializeFixture(NoiseReductionEvaluationCase evaluationCase) throws IOException {
        Path fixturePath = evaluationCase.resolveFixturePath();
        String fileName = fixturePath.getFileName().toString();
        if (fileName.endsWith(".b64")) {
            String encoded = Files.readString(fixturePath).replaceAll("\\s+", "");
            byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
            String materializedName = fileName.substring(0, fileName.length() - 4);
            Path tempFile = Files.createTempFile("noise-reduction-fixture-", "-" + materializedName);
            Files.write(tempFile, decoded);
            tempFile.toFile().deleteOnExit();
            return new MaterializedFixture(tempFile, detectFileType(materializedName), materializedName);
        }
        return new MaterializedFixture(fixturePath, detectFileType(fileName), fileName);
    }

    private static String detectFileType(String fileName) {
        if (fileName.endsWith(".md")) {
            return "text/markdown";
        }
        if (fileName.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (fileName.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return "application/octet-stream";
    }

    private static List<DocumentCleaningStep> createSteps() {
        LowSignalBlockRuleEngine ruleEngine = new LowSignalBlockRuleEngine(
                PROPERTIES,
                new LowSignalProtectionRules(),
                new LowSignalStrongDropRules(),
                new LowSignalScoreRules()
        );
        return List.of(
                new RemoveEmptyBlocksStep(),
                new NormalizeCharactersStep(),
                new NormalizeWhitespaceStep(),
                new RemoveRepeatedHeadersFootersStep(),
                new RemovePageNumbersStep(),
                new RemoveTocFragmentsStep(),
                new RepairBrokenLinesAndParagraphsStep(),
                new SplitPayloadHeavyBlocksStep(),
                new DropLowSignalBlocksStep(ruleEngine),
                new DeduplicateBlocksStep(),
                new NormalizeDocumentMetadataStep(),
                new BuildQualitySummaryStep(PROPERTIES)
        );
    }

    private record MaterializedFixture(Path path, String fileType, String fileName) {
    }
}
