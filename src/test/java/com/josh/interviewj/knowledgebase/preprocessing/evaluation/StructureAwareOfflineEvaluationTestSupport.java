package com.josh.interviewj.knowledgebase.preprocessing.evaluation;

import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkCandidate;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkCandidateFactory;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ParentContextTemplateBuilder;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.StructureAwareChunkingResult;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.StructureAwareChunkingService;
import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.parser.DocxDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.parser.DocumentParserRegistry;
import com.josh.interviewj.knowledgebase.preprocessing.parser.MarkdownDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.parser.PdfDocumentParser;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DefaultDocumentPreprocessingPipeline;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentCleaningStep;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInput;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInputAdapter;
import com.josh.interviewj.knowledgebase.service.KbChunkingService;
import com.josh.interviewj.knowledgebase.preprocessing.steps.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Test support for structure-aware chunking offline evaluation.
 */
public final class StructureAwareOfflineEvaluationTestSupport {

    private static final int REPORT_TOP_K = 5;
    private static final DocumentPreprocessingProperties PROPERTIES = new DocumentPreprocessingProperties();
    private static final DefaultDocumentPreprocessingPipeline PIPELINE = new DefaultDocumentPreprocessingPipeline(
            new DocumentParserRegistry(List.of(new PdfDocumentParser(), new DocxDocumentParser(), new MarkdownDocumentParser())),
            PROPERTIES,
            createSteps()
    );
    private static final StructureAwareChunkingService CHUNKING_SERVICE = new StructureAwareChunkingService(
            PROPERTIES,
            new ChunkCandidateFactory(new ParentContextTemplateBuilder(PROPERTIES))
    );
    private static final FixedSizeChunkingInputAdapter FIXED_SIZE_ADAPTER = new FixedSizeChunkingInputAdapter();
    private static final KbChunkingService KB_CHUNKING_SERVICE = new KbChunkingService();

    /**
     * Embedding text template variants for A/B comparison.
     * Template A: Full context (document title + section path + block type)
     * Template B: Minimal context (section path only, no document title or block type)
     */
    public enum EmbeddingTemplate {
        FULL_CONTEXT("Template A: Full context"),
        MINIMAL_CONTEXT("Template B: Minimal context");

        private final String displayName;

        EmbeddingTemplate(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private StructureAwareOfflineEvaluationTestSupport() {
    }

    public static EvaluationRunResult runEvaluation(List<StructureAwareEvaluationCase> cases) {
        List<CaseResult> caseResults = cases.stream()
                .map(StructureAwareOfflineEvaluationTestSupport::evaluateCase)
                .toList();
        Path reportPath = StructureAwareReportWriter.writeReport(caseResults);
        return new EvaluationRunResult(reportPath, caseResults);
    }

    public record EvaluationRunResult(
            Path reportPath,
            List<CaseResult> caseResults
    ) {
    }

    public record CaseResult(
            String caseId,
            String queryType,
            String sourceType,
            int baselineChunkCount,
            int shadowChunkCount,
            boolean anchorHitBaseline,
            boolean anchorHitShadow,
            boolean sectionHintHitBaseline,
            boolean sectionHintHitShadow,
            // Recall@K: all anchors appear in top K chunks
            boolean anchorRecallBaseline,
            boolean anchorRecallShadow,
            int totalDisplayChars,
            int totalEmbeddingChars,
            double parentContextCoverage,
            double sectionHintCoverage,
            // Template A/B comparison results
            Map<EmbeddingTemplate, TemplateResult> templateResults
    ) {
    }

    /**
     * Per-template evaluation result for A/B comparison.
     */
    public record TemplateResult(
            EmbeddingTemplate template,
            boolean anchorHit,
            boolean anchorRecall,
            int embeddingChars,
            double contextCoverage
    ) {
    }

    private static CaseResult evaluateCase(StructureAwareEvaluationCase evaluationCase) {
        try {
            MaterializedFixture fixture = materializeFixture(evaluationCase);
            NormalizedDocument normalizedDocument = PIPELINE.preprocess(fixture.path(), fixture.fileType(), fixture.fileName());
            String sourceType = normalizedDocument.sourceType().name();

            // Baseline: fixed-size chunking using real production pipeline
            List<ChunkCandidate> baselineChunks = generateBaselineChunks(normalizedDocument);

            // Shadow: structure-aware chunking
            StructureAwareChunkingResult shadowResult = CHUNKING_SERVICE.chunk(normalizedDocument);
            List<ChunkCandidate> shadowChunks = shadowResult.candidates();

            // Calculate metrics
            List<String> normalizedAnchors = evaluationCase.expectedAnchors().stream()
                    .map(StructureAwareOfflineEvaluationTestSupport::normalizeText)
                    .toList();
            List<String> normalizedSectionHints = evaluationCase.expectedSectionHints().stream()
                    .map(StructureAwareOfflineEvaluationTestSupport::normalizeText)
                    .toList();

            // Hit@5: check if ANY anchor appears in top K chunks (partial match - at least one anchor found)
            boolean anchorHitBaseline = checkAnchorHit(baselineChunks.subList(0, Math.min(REPORT_TOP_K, baselineChunks.size())), normalizedAnchors);
            boolean anchorHitShadow = checkAnchorHit(shadowChunks.subList(0, Math.min(REPORT_TOP_K, shadowChunks.size())), normalizedAnchors);

            // Recall@5: check if ALL anchors appear in top K chunks (complete match - every anchor found)
            boolean anchorRecallBaseline = checkAnchorRecall(baselineChunks.subList(0, Math.min(REPORT_TOP_K, baselineChunks.size())), normalizedAnchors);
            boolean anchorRecallShadow = checkAnchorRecall(shadowChunks.subList(0, Math.min(REPORT_TOP_K, shadowChunks.size())), normalizedAnchors);

            // Section hint hit: check if section path contains hint
            boolean sectionHintHitBaseline = checkSectionHintHit(baselineChunks.subList(0, Math.min(REPORT_TOP_K, baselineChunks.size())), normalizedSectionHints);
            boolean sectionHintHitShadow = checkSectionHintHit(shadowChunks.subList(0, Math.min(REPORT_TOP_K, shadowChunks.size())), normalizedSectionHints);

            // Parent context coverage: ratio of chunks with hasParentContext=true
            double parentContextCoverage = shadowChunks.isEmpty() ? 0.0 :
                    shadowChunks.stream().filter(c -> Boolean.TRUE.equals(c.metadata().get("hasParentContext"))).count() / (double) shadowChunks.size();

            // Section hint coverage: ratio of chunks with non-empty sectionPath
            double sectionHintCoverage = shadowChunks.isEmpty() ? 0.0 :
                    shadowChunks.stream().filter(c -> !c.sectionPath().isEmpty()).count() / (double) shadowChunks.size();

            // Template A/B comparison: evaluate with different embedding text templates
            Map<EmbeddingTemplate, TemplateResult> templateResults = evaluateTemplateVariants(
                    shadowChunks, normalizedDocument.title(), normalizedAnchors
            );

            return new CaseResult(
                    evaluationCase.id(),
                    evaluationCase.queryType(),
                    sourceType,
                    baselineChunks.size(),
                    shadowChunks.size(),
                    anchorHitBaseline,
                    anchorHitShadow,
                    sectionHintHitBaseline,
                    sectionHintHitShadow,
                    anchorRecallBaseline,
                    anchorRecallShadow,
                    shadowResult.totalDisplayChars(),
                    shadowResult.totalEmbeddingChars(),
                    parentContextCoverage,
                    sectionHintCoverage,
                    templateResults
            );
        } catch (IOException ex) {
            throw new RuntimeException("Failed to evaluate case " + evaluationCase.id(), ex);
        }
    }

    /**
     * Evaluate template variants for A/B comparison.
     */
    private static Map<EmbeddingTemplate, TemplateResult> evaluateTemplateVariants(
            List<ChunkCandidate> chunks,
            String documentTitle,
            List<String> normalizedAnchors
    ) {
        Map<EmbeddingTemplate, TemplateResult> results = new LinkedHashMap<>();

        for (EmbeddingTemplate template : EmbeddingTemplate.values()) {
            // Generate embedding text with the template
            List<ChunkCandidate> templatedChunks = applyEmbeddingTemplate(chunks, documentTitle, template);

            // Template A/B must score the generated embeddingText, otherwise template changes cannot affect retrieval metrics.
            List<ChunkCandidate> topKChunks = templatedChunks.subList(0, Math.min(REPORT_TOP_K, templatedChunks.size()));
            boolean anchorHit = checkAnchorHitOnEmbeddingText(topKChunks, normalizedAnchors);
            boolean anchorRecall = checkAnchorRecallOnEmbeddingText(topKChunks, normalizedAnchors);

            int embeddingChars = templatedChunks.stream()
                    .mapToInt(c -> c.embeddingText().length())
                    .sum();

            double contextCoverage = templatedChunks.isEmpty() ? 0.0 :
                    templatedChunks.stream().filter(c -> !c.embeddingText().equals(c.displayText())).count() / (double) templatedChunks.size();

            results.put(template, new TemplateResult(template, anchorHit, anchorRecall, embeddingChars, contextCoverage));
        }

        return results;
    }

    /**
     * Apply an embedding template to generate new embedding text for each chunk.
     */
    private static List<ChunkCandidate> applyEmbeddingTemplate(
            List<ChunkCandidate> chunks,
            String documentTitle,
            EmbeddingTemplate template
    ) {
        return chunks.stream()
                .map(chunk -> {
                    String newEmbeddingText = generateEmbeddingText(chunk, documentTitle, template);
                    return chunk.toBuilder()
                            .embeddingText(newEmbeddingText)
                            .build();
                })
                .toList();
    }

    /**
     * Generate embedding text based on the template variant.
     */
    private static String generateEmbeddingText(ChunkCandidate chunk, String documentTitle, EmbeddingTemplate template) {
        return switch (template) {
            case FULL_CONTEXT -> {
                // Template A: Full context (document title + section path + block type)
                StringBuilder sb = new StringBuilder();
                if (documentTitle != null && !documentTitle.isBlank()) {
                    sb.append("[文档] ").append(truncateText(documentTitle, 50)).append("\n");
                }
                if (!chunk.sectionPath().isEmpty()) {
                    sb.append("[章节] ").append(String.join(" > ", chunk.sectionPath())).append("\n");
                }
                if (!chunk.blockTypes().isEmpty()) {
                    String primaryType = chunk.blockTypes().get(0);
                    if ("TABLE".equals(primaryType)) {
                        sb.append("[类型] 表格\n");
                    } else if ("CODE".equals(primaryType)) {
                        sb.append("[类型] 代码\n");
                    } else if ("LIST_ITEM".equals(primaryType)) {
                        sb.append("[类型] 列表\n");
                    }
                }
                yield sb.isEmpty() ? chunk.displayText() : sb + "\n" + chunk.displayText();
            }
            case MINIMAL_CONTEXT -> {
                // Template B: Minimal context (section path only, no document title or block type)
                if (!chunk.sectionPath().isEmpty()) {
                    yield "[章节] " + String.join(" > ", chunk.sectionPath()) + "\n\n" + chunk.displayText();
                }
                yield chunk.displayText();
            }
        };
    }

    private static String truncateText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    /**
     * Generate baseline chunks using the real fixed-size chunking pipeline.
     * This mirrors production behavior: FixedSizeChunkingInputAdapter + KbChunkingService.
     */
    private static List<ChunkCandidate> generateBaselineChunks(NormalizedDocument document) {
        // Step 1: Apply retrieval disposition filtering via FixedSizeChunkingInputAdapter
        FixedSizeChunkingInput chunkingInput = FIXED_SIZE_ADAPTER.adapt(document);

        // Step 2: Use KbChunkingService for real fixed-size chunking (1200 chars + 200 overlap)
        List<KbChunkingService.ChunkPart> parts = KB_CHUNKING_SERVICE.chunk(chunkingInput.normalizedTextForChunking());

        // Step 3: Convert to ChunkCandidate format
        List<ChunkCandidate> chunks = new ArrayList<>();
        for (KbChunkingService.ChunkPart part : parts) {
            chunks.add(ChunkCandidate.builder()
                    .chunkIndex(part.chunkIndex())
                    .displayText(part.content())
                    .embeddingText(part.content())
                    .sectionPath(List.of())
                    .metadata(Map.of(
                            "startOffset", part.startPosition(),
                            "endOffset", part.endPosition()
                    ))
                    .build());
        }
        return chunks;
    }

    /**
     * Hit@K: check if ANY anchor appears in the provided chunks (partial match).
     * Returns true if at least one anchor is found in the chunks.
     * This is useful for measuring "top-K retrieval finds something relevant".
     */
    private static boolean checkAnchorHit(List<ChunkCandidate> chunks, List<String> normalizedAnchors) {
        if (normalizedAnchors.isEmpty()) return true;
        String combined = chunks.stream()
                .map(c -> normalizeText(c.displayText()))
                .collect(Collectors.joining(" "));
        return normalizedAnchors.stream().anyMatch(combined::contains);
    }

    private static boolean checkAnchorHitOnEmbeddingText(List<ChunkCandidate> chunks, List<String> normalizedAnchors) {
        if (normalizedAnchors.isEmpty()) return true;
        String combined = chunks.stream()
                .map(c -> normalizeText(c.embeddingText()))
                .collect(Collectors.joining(" "));
        return normalizedAnchors.stream().anyMatch(combined::contains);
    }

    /**
     * Recall@K: check if ALL anchors appear in the provided chunks (complete match).
     * Returns true only if every anchor is found in the chunks.
     * This is stricter than Hit@K and measures "top-K retrieval finds everything expected".
     */
    private static boolean checkAnchorRecall(List<ChunkCandidate> chunks, List<String> normalizedAnchors) {
        if (normalizedAnchors.isEmpty()) return true;
        String combined = chunks.stream()
                .map(c -> normalizeText(c.displayText()))
                .collect(Collectors.joining(" "));
        return normalizedAnchors.stream().allMatch(combined::contains);
    }

    private static boolean checkAnchorRecallOnEmbeddingText(List<ChunkCandidate> chunks, List<String> normalizedAnchors) {
        if (normalizedAnchors.isEmpty()) return true;
        String combined = chunks.stream()
                .map(c -> normalizeText(c.embeddingText()))
                .collect(Collectors.joining(" "));
        return normalizedAnchors.stream().allMatch(combined::contains);
    }

    private static boolean checkSectionHintHit(List<ChunkCandidate> chunks, List<String> normalizedHints) {
        if (normalizedHints.isEmpty()) return true;
        String combined = chunks.stream()
                .map(c -> normalizeText(String.join(" ", c.sectionPath())))
                .collect(Collectors.joining(" "));
        return normalizedHints.stream().anyMatch(hint -> !hint.isEmpty() && combined.contains(hint));
    }

    private static String normalizeText(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}]+", " ")
                .trim();
    }

    private static MaterializedFixture materializeFixture(StructureAwareEvaluationCase evaluationCase) throws IOException {
        Path fixturePath = evaluationCase.resolveFixturePath();
        String fileName = fixturePath.getFileName().toString();
        if (fileName.endsWith(".b64")) {
            String encoded = Files.readString(fixturePath).replaceAll("\\s+", "");
            byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
            String materializedName = fileName.substring(0, fileName.length() - 4);
            Path tempFile = Files.createTempFile("structure-aware-fixture-", "-" + materializedName);
            Files.write(tempFile, decoded);
            tempFile.toFile().deleteOnExit();
            return new MaterializedFixture(tempFile, detectFileType(materializedName), materializedName);
        }
        return new MaterializedFixture(fixturePath, detectFileType(fileName), fileName);
    }

    private static String detectFileType(String fileName) {
        if (fileName.endsWith(".md")) return "text/markdown";
        if (fileName.endsWith(".pdf")) return "application/pdf";
        if (fileName.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }

    private static List<DocumentCleaningStep> createSteps() {
        return List.of(
                new RemoveEmptyBlocksStep(),
                new NormalizeCharactersStep(),
                new NormalizeWhitespaceStep(),
                new RemoveRepeatedHeadersFootersStep(),
                new RemovePageNumbersStep(),
                new RemoveTocFragmentsStep(),
                new RepairBrokenLinesAndParagraphsStep(),
                new SplitPayloadHeavyBlocksStep(),
                new DeduplicateBlocksStep(),
                new EnrichSectionPathStep(PROPERTIES),
                new NormalizeDocumentMetadataStep(),
                new BuildQualitySummaryStep(PROPERTIES)
        );
    }

    private record MaterializedFixture(Path path, String fileType, String fileName) {
    }
}
