package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.PreprocessingTestSupport;
import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentQualitySummary;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.StepMetric;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewBlockDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewOnlyReasonCode;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewTextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildQualitySummaryStepTest {

    @Test
    void apply_BuildsMandatoryQualitySummary() {
        BuildQualitySummaryStep step = new BuildQualitySummaryStep(new DocumentPreprocessingProperties());
        PreprocessingContext context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "a".repeat(250), 0)
        ).toBuilder()
                .stepMetrics(Map.of(
                        "RemoveEmptyBlocks", StepMetric.builder().removedCount(1).build(),
                        "DropLowSignalBlocks", StepMetric.builder().warnedCount(1).build()
                ))
                .build();

        var result = step.apply(context);
        DocumentQualitySummary summary = ((com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument)
                result.documentMetadata().get("normalizedDocument")).qualitySummary();

        assertTrue(summary.fitForMainIndex());
        assertTrue(summary.warningCount() >= 0);
        assertFalse(summary.metrics().isEmpty());
    }

    @Test
    void apply_BuildsCanonicalDispositionMetricsWithoutChangingFitGate() {
        BuildQualitySummaryStep step = new BuildQualitySummaryStep(new DocumentPreprocessingProperties());
        PreprocessingContext context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "a".repeat(250), 0)
        ).toBuilder()
                .stepMetrics(Map.of(
                        "RemoveEmptyBlocks", StepMetric.builder().removedCount(1).build(),
                        "DropLowSignalBlocks", StepMetric.builder()
                                .removedCount(1)
                                .warnedCount(2)
                                .protectedCount(1)
                                .build()
                ))
                .build();

        var result = step.apply(context);
        DocumentQualitySummary summary = ((com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument)
                result.documentMetadata().get("normalizedDocument")).qualitySummary();

        assertEquals(1, summary.droppedLowSignalBlockCount());
        assertEquals(2, summary.softDeindexedLowSignalBlockCount());
        assertEquals(1, summary.protectedLowSignalBlockCount());
        assertEquals(2, summary.legacyWarnedLowSignalBlockCount());
        assertTrue(summary.fitForMainIndex());
        assertEquals(2, summary.metrics().get("legacyWarnedLowSignalBlockCount"));
    }

    @Test
    void apply_BuildsReviewProjectionAndCountersWithoutRedefiningNormalizedBlocks() {
        BuildQualitySummaryStep step = new BuildQualitySummaryStep(new DocumentPreprocessingProperties());
        PreprocessingContext context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "正文", 0, 1).toBuilder()
                        .metadata(Map.of("retrievalDisposition", "KEEP"))
                        .build(),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "附录 payload", 1, 2).toBuilder()
                        .metadata(Map.of(
                                "retrievalDisposition", "SOFT_DEINDEX",
                                "retrievalDispositionReasonCodes", List.of("APPENDIX_SAMPLE_PAYLOAD")
                        ))
                        .build()
        ).appendReviewBlocks(java.util.List.of(
                ReviewTextBlock.builder()
                        .blockOrder(3)
                        .type("footer")
                        .disposition(ReviewBlockDisposition.REVIEW_ONLY)
                        .page(2)
                        .reason(ReviewOnlyReasonCode.REPEATED_LAYOUT_ARTIFACT)
                        .text("footer")
                        .build()
        ));

        var result = step.apply(context);
        var normalizedDocument = (com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument)
                result.documentMetadata().get("normalizedDocument");

        assertEquals(2, normalizedDocument.blocks().size());
        assertEquals(3, normalizedDocument.reviewProjection().blocks().size());
        assertEquals(2, normalizedDocument.qualitySummary().metrics().get("reviewOnlyBlockCount"));
        assertEquals(1, normalizedDocument.qualitySummary().metrics().get("indexBlockCount"));
        assertEquals(2, normalizedDocument.qualitySummary().metrics().get("originalBlockCount"));
        assertEquals(2, normalizedDocument.qualitySummary().metrics().get("normalizedBlockCount"));
    }

    @Test
    void apply_FallbackSoftDeindexBlock_MapsReviewOnlyReasonFromCanonicalReasonCodes() {
        BuildQualitySummaryStep step = new BuildQualitySummaryStep(new DocumentPreprocessingProperties());
        PreprocessingContext context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "附录 payload", 0, 1).toBuilder()
                        .metadata(Map.of(
                                "retrievalDisposition", "SOFT_DEINDEX",
                                "retrievalDispositionReasonCodes", List.of("APPENDIX_SAMPLE_PAYLOAD")
                        ))
                        .build()
        );

        var result = step.apply(context);
        var normalizedDocument = (com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument)
                result.documentMetadata().get("normalizedDocument");
        ReviewTextBlock reviewOnlyBlock = normalizedDocument.reviewProjection().blocks().get(0);

        assertEquals(ReviewBlockDisposition.REVIEW_ONLY, reviewOnlyBlock.disposition());
        assertEquals(ReviewOnlyReasonCode.LOW_SIGNAL_APPENDIX_PAYLOAD, reviewOnlyBlock.reason());
    }

    @Test
    void apply_OnlyReviewOnlyBlocks_DoesNotMarkDocumentFitForMainIndex() {
        BuildQualitySummaryStep step = new BuildQualitySummaryStep(new DocumentPreprocessingProperties());
        String reviewOnlyPayload = "a".repeat(250);
        PreprocessingContext context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, reviewOnlyPayload, 0, 1).toBuilder()
                        .metadata(Map.of(
                                "retrievalDisposition", "SOFT_DEINDEX",
                                "retrievalDispositionReasonCodes", List.of("APPENDIX_SAMPLE_PAYLOAD")
                        ))
                        .build()
        ).toBuilder()
                .stepMetrics(Map.of(
                        "DropLowSignalBlocks", StepMetric.builder().warnedCount(1).build()
                ))
                .build();

        var result = step.apply(context);
        var normalizedDocument = (com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument)
                result.documentMetadata().get("normalizedDocument");

        assertFalse(normalizedDocument.qualitySummary().fitForMainIndex());
        assertEquals(0, normalizedDocument.qualitySummary().metrics().get("normalizedTextLength"));
        assertEquals(true, normalizedDocument.qualitySummary().metrics().get("adapterOutputEmpty"));
        assertEquals(1, normalizedDocument.qualitySummary().metrics().get("reviewOnlyBlockCount"));
        assertEquals(0, normalizedDocument.qualitySummary().metrics().get("indexBlockCount"));
        assertEquals(ReviewOnlyReasonCode.LOW_SIGNAL_APPENDIX_PAYLOAD,
                normalizedDocument.reviewProjection().blocks().get(0).reason());
    }
}
