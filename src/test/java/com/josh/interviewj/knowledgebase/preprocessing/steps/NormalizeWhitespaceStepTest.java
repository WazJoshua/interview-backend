package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.PreprocessingTestSupport;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NormalizeWhitespaceStepTest {

    @Test
    void apply_CollapsesWhitespaceForParagraphs() {
        NormalizeWhitespaceStep step = new NormalizeWhitespaceStep();

        var context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "a   b\n\n\nc", 0)
        );

        var result = step.apply(context);

        assertEquals("a b\nc", result.workingBlocks().get(0).text());
    }
}
