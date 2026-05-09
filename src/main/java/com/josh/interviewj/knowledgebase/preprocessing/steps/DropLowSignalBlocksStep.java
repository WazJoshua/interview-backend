package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalBlockDecision;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.LowSignalBlockRuleEngine;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDispositionReasonCode;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewBlockDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewOnlyReasonCode;
import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewTextBlock;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Order(80)
public class DropLowSignalBlocksStep extends AbstractDocumentCleaningStep {

    private static final String RETRIEVAL_DISPOSITION_TRACE_VERSION = "v1";

    private final LowSignalBlockRuleEngine ruleEngine;

    public DropLowSignalBlocksStep(LowSignalBlockRuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    @Override
    public String getName() {
        return "DropLowSignalBlocks";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        List<NormalizedBlock> retained = new ArrayList<>();
        List<ReviewTextBlock> reviewOnlyBlocks = new ArrayList<>();
        int removed = 0;
        int warned = 0;
        int protectedCount = 0;
        for (NormalizedBlock block : context.workingBlocks()) {
            LowSignalBlockDecision decision = ruleEngine.evaluate(block, context.profile());
            Map<String, Object> metadata = new LinkedHashMap<>(block.metadata());
            metadata.put("retrievalDisposition", decision.retrievalDisposition().name());
            metadata.put(
                    "retrievalDispositionReasonCodes",
                    decision.retrievalDispositionReasonCodes().stream().map(Enum::name).toList()
            );
            metadata.put("retrievalDispositionEvidence", decision.retrievalDispositionEvidence());
            metadata.put("retrievalDispositionTraceVersion", RETRIEVAL_DISPOSITION_TRACE_VERSION);
            metadata.put("dropLowSignalDecision", decision.legacyDecisionType().name());
            metadata.put(
                    "dropLowSignalReasonCodes",
                    decision.legacyReasonCodes().stream().map(Enum::name).toList()
            );
            metadata.put("lowSignalScore", decision.score());
            NormalizedBlock updated = block.toBuilder().metadata(metadata).build();
            if (decision.retrievalDisposition() == RetrievalDisposition.DROP) {
                removed++;
                if (shouldRetainDroppedBlockForReview(decision)) {
                    reviewOnlyBlocks.add(toReviewOnlyBlock(updated, decision));
                }
                continue;
            }
            if (decision.retrievalDisposition() == RetrievalDisposition.SOFT_DEINDEX) {
                warned++;
                reviewOnlyBlocks.add(ReviewTextBlock.builder()
                        .blockOrder(updated.order())
                        .type(updated.type().name().toLowerCase(Locale.ROOT))
                        .disposition(ReviewBlockDisposition.REVIEW_ONLY)
                        .page(updated.pageNumber())
                        .reason(resolveReviewOnlyReason(decision))
                        .text(updated.text())
                        .build());
            }
            if (decision.retrievalDisposition() == RetrievalDisposition.PROTECT) {
                protectedCount++;
            }
            retained.add(updated);
        }
        return withMetric(context, retained, removed, warned, protectedCount)
                .appendReviewBlocks(reviewOnlyBlocks);
    }

    private ReviewOnlyReasonCode resolveReviewOnlyReason(LowSignalBlockDecision decision) {
        if (decision.retrievalDispositionReasonCodes().contains(RetrievalDispositionReasonCode.TOC_NAVIGATION_ARTIFACT)) {
            return ReviewOnlyReasonCode.TOC_NAVIGATION_ARTIFACT;
        }
        return ReviewOnlyReasonCode.LOW_SIGNAL_APPENDIX_PAYLOAD;
    }

    private boolean shouldRetainDroppedBlockForReview(LowSignalBlockDecision decision) {
        return decision.retrievalDispositionReasonCodes().contains(RetrievalDispositionReasonCode.APPENDIX_SAMPLE_PAYLOAD);
    }

    private ReviewTextBlock toReviewOnlyBlock(NormalizedBlock block, LowSignalBlockDecision decision) {
        return ReviewTextBlock.builder()
                .blockOrder(block.order())
                .type(block.type().name().toLowerCase(Locale.ROOT))
                .disposition(ReviewBlockDisposition.REVIEW_ONLY)
                .page(block.pageNumber())
                .reason(resolveReviewOnlyReason(decision))
                .text(block.text())
                .build();
    }
}
