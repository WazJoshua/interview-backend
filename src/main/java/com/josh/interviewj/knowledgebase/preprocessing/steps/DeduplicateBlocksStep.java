package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@Order(90)
public class DeduplicateBlocksStep extends AbstractDocumentCleaningStep {

    @Override
    public String getName() {
        return "DeduplicateBlocks";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        Set<String> seen = new LinkedHashSet<>();
        List<NormalizedBlock> retained = new ArrayList<>();
        int removed = 0;
        for (NormalizedBlock block : context.workingBlocks()) {
            String normalized = normalize(block.text());
            boolean candidate = normalized.length() >= 30
                    && (block.type() == NormalizedBlockType.PARAGRAPH || block.type() == NormalizedBlockType.UNKNOWN);
            if (candidate && !seen.add(normalized)) {
                removed++;
                continue;
            }
            retained.add(block);
        }
        return withMetric(context, retained, removed, 0, 0);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
