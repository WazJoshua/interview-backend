package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.PreprocessingTestSupport;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizeCharactersStepTest {

    @Test
    void apply_RemovesBomAndZeroWidthWithoutCorruptingCommands() {
        NormalizeCharactersStep step = new NormalizeCharactersStep();

        var context = PreprocessingTestSupport.context(
                PreprocessingTestSupport.block(NormalizedBlockType.CODE, "\uFEFF./gradlew\u200B test", 0)
        );

        var result = step.apply(context);

        assertEquals("./gradlew test", result.workingBlocks().get(0).text());
        assertTrue(result.stepMetrics().containsKey("NormalizeCharacters"));
    }
}
