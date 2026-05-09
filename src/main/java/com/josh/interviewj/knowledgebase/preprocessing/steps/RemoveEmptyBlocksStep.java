package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(10)
public class RemoveEmptyBlocksStep extends AbstractDocumentCleaningStep {

    @Override
    public String getName() {
        return "RemoveEmptyBlocks";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        List<NormalizedBlock> retained = context.workingBlocks().stream()
                .filter(block -> !block.text().trim().isEmpty())
                .filter(block -> !(block.type() == NormalizedBlockType.UNKNOWN && block.text().trim().matches("^[\\p{Punct}\\s]+$")))
                .toList();
        return withMetric(context, retained, context.workingBlocks().size() - retained.size(), 0, 0);
    }
}
