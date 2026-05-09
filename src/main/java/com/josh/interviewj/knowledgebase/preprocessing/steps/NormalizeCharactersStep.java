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
@Order(20)
public class NormalizeCharactersStep extends AbstractDocumentCleaningStep {

    @Override
    public String getName() {
        return "NormalizeCharacters";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        List<NormalizedBlock> normalized = context.workingBlocks().stream()
                .map(this::normalizeBlock)
                .toList();
        return withMetric(context, normalized, 0, 0, 0);
    }

    private NormalizedBlock normalizeBlock(NormalizedBlock block) {
        String text = block.text()
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\u0000", "")
                .replace('\r', '\n');
        if (block.type() != NormalizedBlockType.CODE) {
            text = text
                    .replace('\u2018', '\'')
                    .replace('\u2019', '\'')
                    .replace('\u201C', '"')
                    .replace('\u201D', '"')
                    .replace('\u00A0', ' ')
                    .replace('\u2013', '-')
                    .replace('\u2014', '-');
        }
        Map<String, Object> metadata = new LinkedHashMap<>(block.metadata());
        metadata.put("cleaningActions", appendAction(block.metadata(), "NormalizeCharacters"));
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
