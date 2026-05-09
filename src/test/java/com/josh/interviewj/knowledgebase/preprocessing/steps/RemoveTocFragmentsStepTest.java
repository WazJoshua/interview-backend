package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.PreprocessingTestSupport;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewBlockDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewOnlyReasonCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoveTocFragmentsStepTest {

    @Test
    void apply_RemovesDottedTocFragments() {
        RemoveTocFragmentsStep step = new RemoveTocFragmentsStep();

        var context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "1. Intro .......... 3", 0),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "真实标题", 1)
        );

        var result = step.apply(context);

        assertEquals(1, result.workingBlocks().size());
        assertEquals(1, result.reviewProjection().blocks().size());
        assertEquals(ReviewBlockDisposition.REVIEW_ONLY, result.reviewProjection().blocks().get(0).disposition());
        assertEquals(ReviewOnlyReasonCode.TOC_NAVIGATION_ARTIFACT, result.reviewProjection().blocks().get(0).reason());
    }
}
