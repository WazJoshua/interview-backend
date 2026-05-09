package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.PreprocessingTestSupport;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoveEmptyBlocksStepTest {

    @Test
    void apply_RemovesBlankAndDecorativeUnknownBlocks() {
        RemoveEmptyBlocksStep step = new RemoveEmptyBlocksStep();

        var context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "hello", 0),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "   ", 1),
                PreprocessingTestSupport.block(NormalizedBlockType.UNKNOWN, "---", 2)
        );

        var result = step.apply(context);

        assertEquals(1, result.workingBlocks().size());
        assertEquals(2, result.stepMetrics().get("RemoveEmptyBlocks").removedCount());
    }
}
