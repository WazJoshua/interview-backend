package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarning;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentCleaningStep;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.StepMetric;

import java.util.List;

abstract class AbstractDocumentCleaningStep implements DocumentCleaningStep {

    protected PreprocessingContext withMetric(
            PreprocessingContext context,
            List<NormalizedBlock> nextBlocks,
            int removedCount,
            int warnedCount,
            int protectedCount
    ) {
        return context.withWorkingBlocks(nextBlocks)
                .addStepMetric(
                        getName(),
                        StepMetric.builder()
                                .inputBlockCount(context.workingBlocks().size())
                                .outputBlockCount(nextBlocks.size())
                                .removedCount(removedCount)
                                .warnedCount(warnedCount)
                                .protectedCount(protectedCount)
                                .build()
                );
    }

    protected PreprocessingContext withMetricAndWarnings(
            PreprocessingContext context,
            List<NormalizedBlock> nextBlocks,
            List<DocumentWarning> warnings,
            int removedCount,
            int warnedCount,
            int protectedCount
    ) {
        return context.withWarnings(warnings)
                .withWorkingBlocks(nextBlocks)
                .addStepMetric(
                        getName(),
                        StepMetric.builder()
                                .inputBlockCount(context.workingBlocks().size())
                                .outputBlockCount(nextBlocks.size())
                                .removedCount(removedCount)
                                .warnedCount(warnedCount)
                                .protectedCount(protectedCount)
                                .build()
                );
    }
}
