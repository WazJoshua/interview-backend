package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarning;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Order(100)
public class NormalizeDocumentMetadataStep extends AbstractDocumentCleaningStep {

    @Override
    public String getName() {
        return "NormalizeDocumentMetadata";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        List<String> warningCodes = context.warnings().stream()
                .map(DocumentWarning::code)
                .distinct()
                .sorted()
                .toList();
        PreprocessingContext updated = context
                .putDocumentMetadata("preprocessingVersion", "v1")
                .putDocumentMetadata("sourceType", context.parsedDocument().sourceType().name())
                .putDocumentMetadata("warningCodes", warningCodes)
                .putDocumentMetadata("warningCount", context.warnings().size());
        return updated.addStepMetric(
                getName(),
                com.josh.interviewj.knowledgebase.preprocessing.pipeline.StepMetric.builder()
                        .inputBlockCount(context.workingBlocks().size())
                        .outputBlockCount(context.workingBlocks().size())
                        .warnedCount(warningCodes.size())
                        .build()
        );
    }
}
