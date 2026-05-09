package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.PreprocessingTestSupport;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplitPayloadHeavyBlocksStepTest {

    @Test
    void apply_ExplanationPlusPayload_SplitsIntoSeparateBlocks() {
        SplitPayloadHeavyBlocksStep step = new SplitPayloadHeavyBlocksStep();
        var context = PreprocessingTestSupport.context(
                DocumentSourceType.MARKDOWN,
                PreprocessingTestSupport.block(
                        NormalizedBlockType.PARAGRAPH,
                        """
                                Appendix sample
                                This example payload shows reference-only fields.
                                token=abcdef0123456789
                                payload={"a":1,"b":2}
                                """,
                        0
                ).toBuilder()
                        .sectionPath(List.of("KB Guide", "附录", "Samples"))
                        .metadata(Map.of())
                        .build()
        );

        var result = step.apply(context);

        assertEquals(2, result.workingBlocks().size());
        assertEquals("Appendix sample\nThis example payload shows reference-only fields.", result.workingBlocks().get(0).text());
        assertEquals("token=abcdef0123456789\npayload={\"a\":1,\"b\":2}", result.workingBlocks().get(1).text());
        assertEquals("explanation", result.workingBlocks().get(0).metadata().get("payloadSplitRole"));
        assertEquals("payload", result.workingBlocks().get(1).metadata().get("payloadSplitRole"));
        assertEquals(0, result.workingBlocks().get(0).order());
        assertEquals(1, result.workingBlocks().get(1).order());
    }

    @Test
    void apply_PdfMixedBlockWithoutStableBoundary_FallsBackToKeepWithTrace() {
        SplitPayloadHeavyBlocksStep step = new SplitPayloadHeavyBlocksStep();
        var context = PreprocessingTestSupport.context(
                DocumentSourceType.PDF,
                PreprocessingTestSupport.block(
                        NormalizedBlockType.PARAGRAPH,
                        """
                                Appendix sample token=abcdef0123456789
                                AUTH_001 invalid token
                                value_a=0000000000000000000000000000000000
                                """,
                        0,
                        1
                ).toBuilder()
                        .metadata(Map.of("lineTexts", List.of(
                                "Appendix sample token=abcdef0123456789",
                                "AUTH_001 invalid token",
                                "value_a=0000000000000000000000000000000000"
                        )))
                        .build()
        );

        var result = step.apply(context);

        assertEquals(1, result.workingBlocks().size());
        assertEquals("kept_unstable_boundary", result.workingBlocks().get(0).metadata().get("payloadSplitOutcome"));
        assertTrue(((List<?>) result.workingBlocks().get(0).metadata().get("cleaningActions")).contains("SplitPayloadHeavyBlocks"));
    }
}
