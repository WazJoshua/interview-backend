package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.PreprocessingTestSupport;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewBlockDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewOnlyReasonCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoveRepeatedHeadersFootersStepTest {

    @Test
    void apply_RemovesRepeatedHeadersAcrossPages() {
        RemoveRepeatedHeadersFootersStep step = new RemoveRepeatedHeadersFootersStep();

        var context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "Company Handbook", 0, 1),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "body-1", 1, 1),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "Company Handbook", 2, 2),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "body-2", 3, 2),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "Company Handbook", 4, 3),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "body-3", 5, 3)
        );

        var result = step.apply(context);

        assertEquals(3, result.workingBlocks().size());
        assertEquals(3, result.stepMetrics().get("RemoveRepeatedHeadersFooters").removedCount());
        assertEquals(3, result.reviewProjection().blocks().size());
        assertEquals(ReviewBlockDisposition.REVIEW_ONLY, result.reviewProjection().blocks().get(0).disposition());
        assertEquals(ReviewOnlyReasonCode.REPEATED_LAYOUT_ARTIFACT, result.reviewProjection().blocks().get(0).reason());
    }
}
