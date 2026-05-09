package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewBlockDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewOnlyReasonCode;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewTextBlock;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@Order(60)
public class RemoveTocFragmentsStep extends AbstractDocumentCleaningStep {

    @Override
    public String getName() {
        return "RemoveTocFragments";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        List<NormalizedBlock> retained = new ArrayList<>();
        List<ReviewTextBlock> reviewOnlyBlocks = new ArrayList<>();
        for (NormalizedBlock block : context.workingBlocks()) {
            if (block.text().trim().matches("(?is).+\\.{3,}\\s*\\d{1,4}$")) {
                reviewOnlyBlocks.add(ReviewTextBlock.builder()
                        .blockOrder(block.order())
                        .type(block.type().name().toLowerCase(Locale.ROOT))
                        .disposition(ReviewBlockDisposition.REVIEW_ONLY)
                        .page(block.pageNumber())
                        .reason(ReviewOnlyReasonCode.TOC_NAVIGATION_ARTIFACT)
                        .text(block.text())
                        .build());
                continue;
            }
            retained.add(block);
        }
        return withMetric(context, retained, context.workingBlocks().size() - retained.size(), 0, 0)
                .appendReviewBlocks(reviewOnlyBlocks);
    }
}
