package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.PreprocessingTestSupport;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepairBrokenLinesAndParagraphsStepTest {

    @Test
    void apply_PdfParagraphContinuation_StillMerges() {
        RepairBrokenLinesAndParagraphsStep step = new RepairBrokenLinesAndParagraphsStep();

        var context = PreprocessingTestSupport.context(
                DocumentSourceType.PDF,
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "this is", 0, 1),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "continued text", 1, 1),
                PreprocessingTestSupport.block(NormalizedBlockType.HEADING, "Heading", 2, 1)
        );

        var result = step.apply(context);

        assertEquals(2, result.workingBlocks().size());
        assertEquals("this is continued text", result.workingBlocks().get(0).text());
    }

    @Test
    void apply_MarkdownParagraphs_DoNotMergeAcrossRealBoundaries() {
        RepairBrokenLinesAndParagraphsStep step = new RepairBrokenLinesAndParagraphsStep();

        var context = PreprocessingTestSupport.context(
                DocumentSourceType.MARKDOWN,
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "this is", 0, 1),
                PreprocessingTestSupport.block(NormalizedBlockType.PARAGRAPH, "continued text", 1, 1),
                PreprocessingTestSupport.block(NormalizedBlockType.HEADING, "Heading", 2, 1)
        );

        var result = step.apply(context);

        assertEquals(3, result.workingBlocks().size());
        assertEquals("this is", result.workingBlocks().get(0).text());
        assertEquals("continued text", result.workingBlocks().get(1).text());
    }
}
