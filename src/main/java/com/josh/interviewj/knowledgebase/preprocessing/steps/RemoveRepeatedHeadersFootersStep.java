package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarning;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarningCategory;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
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
import java.util.stream.Collectors;

@Component
@Order(40)
public class RemoveRepeatedHeadersFootersStep extends AbstractDocumentCleaningStep {

    @Override
    public String getName() {
        return "RemoveRepeatedHeadersFooters";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        Map<Integer, List<NormalizedBlock>> blocksByPage = context.workingBlocks().stream()
                .filter(block -> block.pageNumber() != null)
                .collect(Collectors.groupingBy(
                        NormalizedBlock::pageNumber,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        if (blocksByPage.size() < 3) {
            List<DocumentWarning> warnings = new ArrayList<>(context.warnings());
            warnings.add(DocumentWarning.builder()
                    .code("HEADER_FOOTER_DETECTION_SKIPPED")
                    .category(DocumentWarningCategory.STRUCTURAL)
                    .message("Header/footer detection skipped because page count is too low")
                    .build());
            return withMetricAndWarnings(context, context.workingBlocks(), warnings, 0, 1, 0);
        }

        Map<String, Long> firstBlockCount = blocksByPage.values().stream()
                .map(pageBlocks -> normalizeText(pageBlocks.get(0).text()))
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> lastBlockCount = blocksByPage.values().stream()
                .map(pageBlocks -> normalizeText(pageBlocks.get(pageBlocks.size() - 1).text()))
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));

        List<NormalizedBlock> retained = new ArrayList<>();
        List<ReviewTextBlock> reviewOnlyBlocks = new ArrayList<>();
        int removed = 0;
        for (NormalizedBlock block : context.workingBlocks()) {
            boolean repeatedHeader = isFirstPageBlock(blocksByPage, block)
                    && firstBlockCount.getOrDefault(normalizeText(block.text()), 0L) >= 2;
            boolean repeatedFooter = isLastPageBlock(blocksByPage, block)
                    && lastBlockCount.getOrDefault(normalizeText(block.text()), 0L) >= 2;
            if (repeatedHeader || repeatedFooter) {
                removed++;
                reviewOnlyBlocks.add(ReviewTextBlock.builder()
                        .blockOrder(block.order())
                        .type((repeatedHeader ? NormalizedBlockType.HEADER : NormalizedBlockType.FOOTER).name().toLowerCase(Locale.ROOT))
                        .disposition(ReviewBlockDisposition.REVIEW_ONLY)
                        .page(block.pageNumber())
                        .reason(ReviewOnlyReasonCode.REPEATED_LAYOUT_ARTIFACT)
                        .text(block.text())
                        .build());
                continue;
            }
            retained.add(block);
        }
        return withMetric(context, retained, removed, 0, 0)
                .appendReviewBlocks(reviewOnlyBlocks);
    }

    private boolean isFirstPageBlock(Map<Integer, List<NormalizedBlock>> blocksByPage, NormalizedBlock block) {
        List<NormalizedBlock> pageBlocks = blocksByPage.get(block.pageNumber());
        return pageBlocks != null && !pageBlocks.isEmpty() && pageBlocks.get(0).order() == block.order();
    }

    private boolean isLastPageBlock(Map<Integer, List<NormalizedBlock>> blocksByPage, NormalizedBlock block) {
        List<NormalizedBlock> pageBlocks = blocksByPage.get(block.pageNumber());
        return pageBlocks != null && !pageBlocks.isEmpty() && pageBlocks.get(pageBlocks.size() - 1).order() == block.order();
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
