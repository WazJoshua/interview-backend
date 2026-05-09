package com.josh.interviewj.knowledgebase.preprocessing.evaluation;

import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkCandidate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StructureAwareOfflineEvaluationTest {

    @Test
    void loadEvaluationCases_RequiresMandatoryStructureAwareFields() throws Exception {
        Path file = Files.createTempFile("structure-aware-case-", ".yaml");
        Files.writeString(file, """
                fixture: fixtures/sample.md
                expectedAnchors: []
                expectedSectionHints: []
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StructureAwareEvaluationCase.load(file)
        );

        assertTrue(exception.getMessage().contains("query"));
    }

    @Test
    void runOfflineEvaluation_WritesBucketedStructureAwareReport() throws Exception {
        List<StructureAwareEvaluationCase> cases = StructureAwareEvaluationCase.loadAllFromResources(
                "knowledgebase/evaluation/structure-aware"
        );

        assertFalse(cases.isEmpty(), "Should have at least one evaluation case");

        StructureAwareOfflineEvaluationTestSupport.EvaluationRunResult result =
                StructureAwareOfflineEvaluationTestSupport.runEvaluation(cases);

        assertTrue(Files.exists(result.reportPath()));
        String report = Files.readString(result.reportPath());

        // Verify report contains required sections
        assertTrue(report.contains("## Summary"));
        assertTrue(report.contains("## By Query Type"));
        assertTrue(report.contains("## By Source Type"));
        assertTrue(report.contains("Anchor Hit@5"));
        assertTrue(report.contains("Section Hint Hit@5"));
        assertTrue(report.contains("Rollout Recommendation"));
    }

    @Test
    void runOfflineEvaluation_ProducesTemplateComparisonForRolloutGate() throws Exception {
        List<StructureAwareEvaluationCase> cases = StructureAwareEvaluationCase.loadAllFromResources(
                "knowledgebase/evaluation/structure-aware"
        );

        StructureAwareOfflineEvaluationTestSupport.EvaluationRunResult result =
                StructureAwareOfflineEvaluationTestSupport.runEvaluation(cases);

        // Verify metrics are computed
        for (StructureAwareOfflineEvaluationTestSupport.CaseResult caseResult : result.caseResults()) {
            assertTrue(caseResult.baselineChunkCount() >= 0);
            assertTrue(caseResult.shadowChunkCount() >= 0);
            assertTrue(caseResult.parentContextCoverage() >= 0 && caseResult.parentContextCoverage() <= 1);
            assertTrue(caseResult.sectionHintCoverage() >= 0 && caseResult.sectionHintCoverage() <= 1);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluateTemplateVariants_UsesEmbeddingTextForTemplateComparison() throws Exception {
        ChunkCandidate chunk = ChunkCandidate.builder()
                .chunkIndex(0)
                .displayText("Only body text")
                .embeddingText("Only body text")
                .sectionPath(List.of("Regular Section"))
                .blockTypes(List.of("PARAGRAPH"))
                .metadata(Map.of())
                .build();

        Method method = StructureAwareOfflineEvaluationTestSupport.class.getDeclaredMethod(
                "evaluateTemplateVariants",
                List.class,
                String.class,
                List.class
        );
        method.setAccessible(true);

        Map<StructureAwareOfflineEvaluationTestSupport.EmbeddingTemplate, StructureAwareOfflineEvaluationTestSupport.TemplateResult> results =
                (Map<StructureAwareOfflineEvaluationTestSupport.EmbeddingTemplate, StructureAwareOfflineEvaluationTestSupport.TemplateResult>) method.invoke(
                        null,
                        List.of(chunk),
                        "Critical Anchor",
                        List.of("critical anchor")
                );

        assertTrue(results.get(StructureAwareOfflineEvaluationTestSupport.EmbeddingTemplate.FULL_CONTEXT).anchorHit(),
                "Full-context template should match anchors introduced by embedding context");
        assertFalse(results.get(StructureAwareOfflineEvaluationTestSupport.EmbeddingTemplate.MINIMAL_CONTEXT).anchorHit(),
                "Minimal template should not match anchors that only exist in document title context");
    }
}
