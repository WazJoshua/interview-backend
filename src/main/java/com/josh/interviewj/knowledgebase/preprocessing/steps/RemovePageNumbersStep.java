package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(50)
public class RemovePageNumbersStep extends AbstractDocumentCleaningStep {

    private static final String PAGE_NUMBER_PATTERN = "^(page\\s+\\d{1,4}|[-(\\[]?\\d{1,3}[)\\].-]?)$";

    @Override
    public String getName() {
        return "RemovePageNumbers";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        List<NormalizedBlock> retained = context.workingBlocks().stream()
                .filter(block -> !block.text().trim().toLowerCase().matches(PAGE_NUMBER_PATTERN))
                .toList();
        return withMetric(context, retained, context.workingBlocks().size() - retained.size(), 0, 0);
    }
}
