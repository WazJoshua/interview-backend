package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.PreprocessingTestSupport;
import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalBlockRuleEngine;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalProtectionRules;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalScoreRules;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalStrongDropRules;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewBlockDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewOnlyReasonCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropLowSignalBlocksStepTest {

    @Test
    void apply_KeepsWarnedBlocksAndRemovesDroppedBlocks() {
        DropLowSignalBlocksStep step = new DropLowSignalBlocksStep(
                new LowSignalBlockRuleEngine(
                        new DocumentPreprocessingProperties(),
                        new LowSignalProtectionRules(),
                        new LowSignalStrongDropRules(),
                        new LowSignalScoreRules()
                )
        );

        var context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.UNKNOWN, "------", 0),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "tiny", 1),
                PreprocessingTestSupport.block(NormalizedBlockType.CODE, "./gradlew test", 2)
        );

        var result = step.apply(context);

        assertEquals(2, result.workingBlocks().size());
        assertEquals(1, result.stepMetrics().get("DropLowSignalBlocks").removedCount());
        assertEquals(1, result.stepMetrics().get("DropLowSignalBlocks").warnedCount());
    }

    @Test
    void apply_WritesCanonicalTraceAndRetainsLegacyFieldsDuringMigration() {
        DropLowSignalBlocksStep step = new DropLowSignalBlocksStep(
                new LowSignalBlockRuleEngine(
                        new DocumentPreprocessingProperties(),
                        new LowSignalProtectionRules(),
                        new LowSignalStrongDropRules(),
                        new LowSignalScoreRules()
                )
        );

        var context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.UNKNOWN, "------", 0),
                PreprocessingTestSupport.block(
                        NormalizedBlockType.PARAGRAPH,
                        """
                                Appendix sample
                                key=value........................................
                                token=abcdef0123456789..........................
                                payload={"a":1,"b":2,"c":3}....................
                                sample_a=0000000000000000000000000000000000.....
                                sample_b=1111111111111111111111111111111111.....
                                sample_c=2222222222222222222222222222222222.....
                                sample_d=3333333333333333333333333333333333.....
                                sample_e=4444444444444444444444444444444444.....
                                """,
                        1
                ).toBuilder().sectionPath(java.util.List.of("Appendix")).build(),
                PreprocessingTestSupport.block(NormalizedBlockType.CODE, "./gradlew test", 2)
        );

        var result = step.apply(context);

        assertEquals(2, result.workingBlocks().size());
        var appendixMetadata = result.workingBlocks().get(0).metadata();
        assertEquals("SOFT_DEINDEX", appendixMetadata.get("retrievalDisposition"));
        assertEquals(
                java.util.List.of("APPENDIX_SAMPLE_PAYLOAD"),
                appendixMetadata.get("retrievalDispositionReasonCodes")
        );
        assertEquals(
                java.util.List.of("reason:APPENDIX_SAMPLE_PAYLOAD"),
                appendixMetadata.get("retrievalDispositionEvidence")
        );
        assertEquals("v1", appendixMetadata.get("retrievalDispositionTraceVersion"));
        assertEquals("WARN", appendixMetadata.get("dropLowSignalDecision"));
        assertEquals(
                java.util.List.of("WARN_POSSIBLE_APPENDIX_SAMPLE_DATA"),
                appendixMetadata.get("dropLowSignalReasonCodes")
        );
        assertTrue(((Number) appendixMetadata.get("lowSignalScore")).doubleValue() > 0.0D);
        assertEquals(1, result.reviewProjection().blocks().size());
    }

    @Test
    void apply_SoftDeindexedPayload_RetainedForReviewProjectionAndExcludedAtChunkingBoundary() {
        DropLowSignalBlocksStep step = new DropLowSignalBlocksStep(
                new LowSignalBlockRuleEngine(
                        new DocumentPreprocessingProperties(),
                        new LowSignalProtectionRules(),
                        new LowSignalStrongDropRules(),
                        new LowSignalScoreRules()
                )
        );

        var context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(
                        NormalizedBlockType.PARAGRAPH,
                        """
                                Appendix sample
                                token=abcdef0123456789
                                payload={"a":1,"b":2,"c":3}
                                sample_a=0000000000000000000000000000000000
                                sample_b=1111111111111111111111111111111111
                                sample_c=2222222222222222222222222222222222
                                sample_d=3333333333333333333333333333333333
                                sample_e=4444444444444444444444444444444444
                                """,
                        1
                ).toBuilder().sectionPath(java.util.List.of("Appendix")).build()
        );

        var result = step.apply(context);
        var retainedBlock = result.workingBlocks().get(0);

        assertEquals("SOFT_DEINDEX", retainedBlock.metadata().get("retrievalDisposition"));
        assertEquals(ReviewBlockDisposition.REVIEW_ONLY, result.reviewProjection().blocks().get(0).disposition());
        assertEquals(ReviewOnlyReasonCode.LOW_SIGNAL_APPENDIX_PAYLOAD, result.reviewProjection().blocks().get(0).reason());
    }

    @Test
    void apply_DroppedAppendixPayload_StillRetainedForReviewArtifact() {
        // Aggressive profile with dropAppendixSamples=true
        var aggressiveProfile = DocumentPreprocessingProperties.ProfileProperties.builder()
                .dropAppendixSamples(true)
                .build();

        DropLowSignalBlocksStep step = new DropLowSignalBlocksStep(
                new LowSignalBlockRuleEngine(
                        new DocumentPreprocessingProperties(),
                        new LowSignalProtectionRules(),
                        new LowSignalStrongDropRules(),
                        new LowSignalScoreRules()
                )
        );

        var baseContext = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(
                        NormalizedBlockType.PARAGRAPH,
                        """
                                Appendix sample
                                token=abcdef0123456789
                                payload={"a":1,"b":2,"c":3}
                                sample_a=0000000000000000000000000000000000
                                sample_b=1111111111111111111111111111111111
                                sample_c=2222222222222222222222222222222222
                                sample_d=3333333333333333333333333333333333
                                sample_e=4444444444444444444444444444444444
                                """,
                        1
                ).toBuilder().sectionPath(java.util.List.of("Appendix")).build()
        );
        var context = baseContext.toBuilder().profile(aggressiveProfile).build();

        var result = step.apply(context);

        // Block should be removed from workingBlocks
        assertTrue(result.workingBlocks().isEmpty(), "Dropped appendix payload should not be in workingBlocks");
        assertEquals(1, result.stepMetrics().get("DropLowSignalBlocks").removedCount());

        // Block should still be retained in reviewProjection for audit artifact
        assertEquals(1, result.reviewProjection().blocks().size(),
                "Dropped appendix payload should be retained in reviewProjection for audit artifact");
        assertEquals(ReviewBlockDisposition.REVIEW_ONLY, result.reviewProjection().blocks().get(0).disposition());
        assertEquals(ReviewOnlyReasonCode.LOW_SIGNAL_APPENDIX_PAYLOAD, result.reviewProjection().blocks().get(0).reason());
    }

    @Test
    void apply_DroppedNonPayloadGarbage_DoesNotLeakIntoReviewProjection() {
        // Aggressive profile with dropAppendixSamples=true
        var aggressiveProfile = DocumentPreprocessingProperties.ProfileProperties.builder()
                .dropAppendixSamples(true)
                .build();

        DropLowSignalBlocksStep step = new DropLowSignalBlocksStep(
                new LowSignalBlockRuleEngine(
                        new DocumentPreprocessingProperties(),
                        new LowSignalProtectionRules(),
                        new LowSignalStrongDropRules(),
                        new LowSignalScoreRules()
                )
        );

        // Hard-drop garbage: separator pattern (------)
        var baseContext = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.UNKNOWN, "------", 0)
        );
        var context = baseContext.toBuilder().profile(aggressiveProfile).build();

        var result = step.apply(context);

        // Block should be removed from workingBlocks
        assertTrue(result.workingBlocks().isEmpty(), "Separator pattern should be dropped");

        // Hard-drop garbage should NOT appear in reviewProjection
        assertTrue(result.reviewProjection().blocks().isEmpty(),
                "Hard-drop separator pattern should not leak into reviewProjection");
    }
}
