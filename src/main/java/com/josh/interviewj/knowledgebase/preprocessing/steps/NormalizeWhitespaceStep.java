package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(30)
public class NormalizeWhitespaceStep extends AbstractDocumentCleaningStep {

    @Override
    public String getName() {
        return "NormalizeWhitespace";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        List<NormalizedBlock> normalized = context.workingBlocks().stream()
                .map(this::normalizeBlock)
                .toList();
        return withMetric(context, normalized, 0, 0, 0);
    }

    private NormalizedBlock normalizeBlock(NormalizedBlock block) {
        String text = block.text().replace("\r\n", "\n");
        if (block.type() == NormalizedBlockType.CODE) {
            text = text.strip();
        } else {
            text = text.replaceAll("[ \\t]+", " ")
                    .replaceAll("\\n{3,}", "\n\n")
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("")
                    .strip();
        }
        Map<String, Object> metadata = new LinkedHashMap<>(block.metadata());
        metadata.put("cleaningActions", appendAction(block.metadata(), "NormalizeWhitespace"));
        return block.toBuilder().text(text).metadata(metadata).build();
    }

    @SuppressWarnings("unchecked")
    private List<String> appendAction(Map<String, Object> metadata, String action) {
        List<String> existing = metadata.get("cleaningActions") instanceof List<?> list
                ? (List<String>) list
                : List.of();
        java.util.ArrayList<String> actions = new java.util.ArrayList<>(existing);
        actions.add(action);
        return actions;
    }
}
