package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.PreprocessingTestSupport;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemovePageNumbersStepTest {

    @Test
    void apply_RemovesOnlyTypicalPageNumberPatterns() {
        RemovePageNumbersStep step = new RemovePageNumbersStep();

        var context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "Page 3", 0),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "2026", 1),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "正文 123", 2)
        );

        var result = step.apply(context);

        assertEquals(2, result.workingBlocks().size());
    }
}
