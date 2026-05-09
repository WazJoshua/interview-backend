package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Order(70)
public class RepairBrokenLinesAndParagraphsStep extends AbstractDocumentCleaningStep {

    @Override
    public String getName() {
        return "RepairBrokenLinesAndParagraphs";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        if (context.parsedDocument().sourceType() != DocumentSourceType.PDF) {
            return withMetric(context, context.workingBlocks(), 0, 0, 0);
        }

        List<NormalizedBlock> repaired = new ArrayList<>();
        NormalizedBlock pending = null;
        for (NormalizedBlock block : context.workingBlocks()) {
            if (pending == null) {
                pending = block;
                continue;
            }
            if (canMerge(pending, block)) {
                pending = pending.toBuilder()
                        .text(join(pending.text(), block.text()))
                        .metadata(mergeMetadata(pending, "RepairBrokenLinesAndParagraphs"))
                        .build();
            } else {
                repaired.add(pending);
                pending = block;
            }
        }
        if (pending != null) {
            repaired.add(pending);
        }
        return withMetric(context, repaired, context.workingBlocks().size() - repaired.size(), 0, 0);
    }

    private boolean canMerge(NormalizedBlock left, NormalizedBlock right) {
        if (left.type() != NormalizedBlockType.PARAGRAPH || right.type() != NormalizedBlockType.PARAGRAPH) {
            return false;
        }
        if (left.pageNumber() != null && right.pageNumber() != null && !left.pageNumber().equals(right.pageNumber())) {
            return false;
        }
        if (left.text().endsWith(":") || left.text().endsWith(".") || left.text().endsWith("!") || left.text().endsWith("?")) {
            return false;
        }
        if (right.text().isBlank()) {
            return false;
        }
        char first = right.text().charAt(0);
        return Character.isLowerCase(first);
    }

    private String join(String left, String right) {
        return (left + " " + right).replaceAll("\\s+", " ").trim();
    }

    private java.util.Map<String, Object> mergeMetadata(NormalizedBlock block, String action) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(block.metadata());
        @SuppressWarnings("unchecked")
        List<String> actions = metadata.get("cleaningActions") instanceof List<?> list
                ? new ArrayList<>((List<String>) list)
                : new ArrayList<>();
        actions.add(action);
        metadata.put("cleaningActions", actions);
        return metadata;
    }
}
