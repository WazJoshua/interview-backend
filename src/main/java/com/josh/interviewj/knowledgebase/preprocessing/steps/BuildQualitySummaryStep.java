package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentQualitySummary;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarningCategory;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInput;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInputAdapter;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.StepMetric;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewBlockDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewOnlyReasonCode;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewProjection;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewTextBlock;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@Order(110)
public class BuildQualitySummaryStep extends AbstractDocumentCleaningStep {

    private final DocumentPreprocessingProperties properties;
    private final FixedSizeChunkingInputAdapter fixedSizeChunkingInputAdapter = new FixedSizeChunkingInputAdapter();

    public BuildQualitySummaryStep(DocumentPreprocessingProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "BuildQualitySummary";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        FixedSizeChunkingInput chunkingInput = fixedSizeChunkingInputAdapter.adapt(
                NormalizedDocument.builder()
                        .blocks(context.workingBlocks())
                        .warnings(context.warnings())
                        .build()
        );
        String normalizedText = chunkingInput.normalizedTextForChunking();
        int originalBlockCount = context.parsedDocument().blocks().size();
        int normalizedBlockCount = context.workingBlocks().size();
        int indexableBlockCount = chunkingInput.retainedSegments().size();
        double retainedBlockRatio = indexableBlockCount / (double) Math.max(1, originalBlockCount);
        double nonReadableCharRatio = normalizedText.isEmpty()
                ? 1.0D
                : normalizedText.chars().filter(ch -> Character.isISOControl(ch) && !Character.isWhitespace(ch)).count()
                / (double) normalizedText.length();
        boolean adapterOutputEmpty = normalizedText.isBlank();
        boolean fitForMainIndex = retainedBlockRatio >= properties.getQuality().getMinRetainedBlockRatio()
                && nonReadableCharRatio <= properties.getQuality().getMaxNonReadableCharRatio()
                && normalizedText.length() >= properties.getQuality().getMinNormalizedTextChars()
                && !adapterOutputEmpty;
        int droppedLowSignalBlockCount = metricValue(context, "DropLowSignalBlocks");
        int softDeindexedLowSignalBlockCount = metricWarnValue(context, "DropLowSignalBlocks");
        int protectedLowSignalBlockCount = metricProtectedValue(context, "DropLowSignalBlocks");
        ReviewProjection reviewProjection = buildReviewProjection(context);
        int reviewOnlyBlockCount = (int) reviewProjection.blocks().stream()
                .filter(block -> block.disposition() == ReviewBlockDisposition.REVIEW_ONLY)
                .count();
        int indexBlockCount = (int) reviewProjection.blocks().stream()
                .filter(block -> block.disposition() == ReviewBlockDisposition.INDEX)
                .count();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("retainedBlockRatio", retainedBlockRatio);
        metrics.put("nonReadableCharRatio", nonReadableCharRatio);
        metrics.put("normalizedTextLength", normalizedText.length());
        metrics.put("adapterOutputEmpty", adapterOutputEmpty);
        metrics.put("legacyWarnedLowSignalBlockCount", softDeindexedLowSignalBlockCount);
        metrics.put("reviewOnlyBlockCount", reviewOnlyBlockCount);
        metrics.put("indexBlockCount", indexBlockCount);
        metrics.put("originalBlockCount", originalBlockCount);
        metrics.put("normalizedBlockCount", normalizedBlockCount);
        metrics.put("stepMetrics", context.stepMetrics());

        DocumentQualitySummary qualitySummary = DocumentQualitySummary.builder()
                .originalBlockCount(originalBlockCount)
                .normalizedBlockCount(normalizedBlockCount)
                .removedEmptyBlockCount(metricValue(context, "RemoveEmptyBlocks"))
                .removedHeaderFooterCount(metricValue(context, "RemoveRepeatedHeadersFooters"))
                .removedPageNumberCount(metricValue(context, "RemovePageNumbers"))
                .removedTocFragmentCount(metricValue(context, "RemoveTocFragments"))
                .droppedLowSignalBlockCount(droppedLowSignalBlockCount)
                .softDeindexedLowSignalBlockCount(softDeindexedLowSignalBlockCount)
                .protectedLowSignalBlockCount(protectedLowSignalBlockCount)
                .legacyWarnedLowSignalBlockCount(softDeindexedLowSignalBlockCount)
                .warnedLowSignalBlockCount(softDeindexedLowSignalBlockCount)
                .deduplicatedBlockCount(metricValue(context, "DeduplicateBlocks"))
                .warningCount(context.warnings().size())
                .hasStructuralWarnings(context.warnings().stream().anyMatch(warning -> warning.category() == DocumentWarningCategory.STRUCTURAL))
                .hasReadabilityWarnings(
                        context.warnings().stream().anyMatch(warning -> warning.category() == DocumentWarningCategory.READABILITY)
                                || softDeindexedLowSignalBlockCount > 0
                                || nonReadableCharRatio > properties.getQuality().getMaxNonReadableCharRatio()
                )
                .fitForMainIndex(fitForMainIndex)
                .metrics(metrics)
                .build();

        NormalizedDocument normalizedDocument = NormalizedDocument.builder()
                .sourceType(context.parsedDocument().sourceType())
                .fileName(context.parsedDocument().fileName())
                .title(context.parsedDocument().title())
                .metadata(context.documentMetadata())
                .blocks(context.workingBlocks())
                .warnings(context.warnings())
                .qualitySummary(qualitySummary)
                .reviewProjection(reviewProjection)
                .build();

        return context
                .putQualitySignal("retainedBlockRatio", retainedBlockRatio)
                .putQualitySignal("nonReadableCharRatio", nonReadableCharRatio)
                .putQualitySignal("normalizedTextLength", normalizedText.length())
                .putQualitySignal("adapterOutputEmpty", adapterOutputEmpty)
                .putQualitySignal("reviewOnlyBlockCount", reviewOnlyBlockCount)
                .putQualitySignal("indexBlockCount", indexBlockCount)
                .putDocumentMetadata("normalizedDocument", normalizedDocument)
                .withReviewProjection(reviewProjection)
                .addStepMetric(
                        getName(),
                        StepMetric.builder()
                                .inputBlockCount(context.workingBlocks().size())
                                .outputBlockCount(context.workingBlocks().size())
                                .warnedCount(context.warnings().size())
                                .build()
                );
    }

    private int metricValue(PreprocessingContext context, String name) {
        return context.stepMetrics().getOrDefault(name, StepMetric.empty(context.workingBlocks().size())).removedCount();
    }

    private int metricWarnValue(PreprocessingContext context, String name) {
        return context.stepMetrics().getOrDefault(name, StepMetric.empty(context.workingBlocks().size())).warnedCount();
    }

    private int metricProtectedValue(PreprocessingContext context, String name) {
        return context.stepMetrics().getOrDefault(name, StepMetric.empty(context.workingBlocks().size())).protectedCount();
    }

    private ReviewProjection buildReviewProjection(PreprocessingContext context) {
        List<ReviewTextBlock> projectionBlocks = new ArrayList<>(context.reviewProjection().blocks());
        Set<Integer> projectedOrders = new LinkedHashSet<>();
        context.reviewProjection().blocks().stream()
                .map(ReviewTextBlock::blockOrder)
                .forEach(projectedOrders::add);

        for (NormalizedBlock block : context.workingBlocks()) {
            if (projectedOrders.contains(block.order())) {
                continue;
            }
            projectionBlocks.add(toFallbackReviewBlock(block));
        }
        return ReviewProjection.builder()
                .blocks(projectionBlocks)
                .build();
    }

    private ReviewTextBlock toFallbackReviewBlock(NormalizedBlock block) {
        ReviewBlockDisposition disposition = resolveDisposition(block);
        return ReviewTextBlock.builder()
                .blockOrder(block.order())
                .type(block.type().name().toLowerCase(Locale.ROOT))
                .disposition(disposition)
                .page(block.pageNumber())
                .reason(disposition == ReviewBlockDisposition.REVIEW_ONLY ? resolveReviewOnlyReason(block) : null)
                .text(block.text())
                .build();
    }

    private ReviewBlockDisposition resolveDisposition(NormalizedBlock block) {
        Object retrievalDisposition = block.metadata().get("retrievalDisposition");
        if (retrievalDisposition instanceof String dispositionValue
                && RetrievalDisposition.SOFT_DEINDEX.name().equalsIgnoreCase(dispositionValue)) {
            return ReviewBlockDisposition.REVIEW_ONLY;
        }
        return ReviewBlockDisposition.INDEX;
    }

    private ReviewOnlyReasonCode resolveReviewOnlyReason(NormalizedBlock block) {
        List<String> reasonCodes = stringList(block.metadata().get("retrievalDispositionReasonCodes"));
        if (reasonCodes.contains("TOC_NAVIGATION_ARTIFACT")) {
            return ReviewOnlyReasonCode.TOC_NAVIGATION_ARTIFACT;
        }
        return ReviewOnlyReasonCode.LOW_SIGNAL_APPENDIX_PAYLOAD;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
