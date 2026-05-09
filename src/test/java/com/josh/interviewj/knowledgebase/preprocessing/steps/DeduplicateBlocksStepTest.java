package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.PreprocessingTestSupport;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeduplicateBlocksStepTest {

    @Test
    void apply_RemovesRepeatedTemplateParagraphs() {
        DeduplicateBlocksStep step = new DeduplicateBlocksStep();

        var context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "This is a repeated template footer used on every page.", 0),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "This is a repeated template footer used on every page.", 1),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "Unique content paragraph that should remain intact.", 2)
        );

        var result = step.apply(context);

        assertEquals(2, result.workingBlocks().size());
    }
}
