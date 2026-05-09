package com.josh.interviewj.knowledgebase.preprocessing.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoiseReductionOfflineEvaluationTest {

    @Test
    void loadEvaluationCases_RequiresMandatoryFields() throws Exception {
        Path file = Files.createTempFile("noise-reduction-case-", ".yaml");
        Files.writeString(file, """
                fixture: fixtures/appendix-payload.md
                expectedAnchors: []
                expectedDocHints:
                  - appendix-payload
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> NoiseReductionEvaluationCase.load(file)
        );

        assertTrue(exception.getMessage().contains("query"));
    }

    @Test
    void runOfflineEvaluation_WritesStructuredShadowReport() throws Exception {
        List<NoiseReductionEvaluationCase> cases = NoiseReductionEvaluationCase.loadAllFromResources(
                "knowledgebase/evaluation/noise-reduction"
        );

        NoiseReductionOfflineEvaluationTestSupport.EvaluationRunResult result =
                NoiseReductionOfflineEvaluationTestSupport.runEvaluation(cases);

        assertEquals(3, result.caseResults().size());
        assertTrue(Files.exists(result.reportPath()));
        String report = Files.readString(result.reportPath());
        assertTrue(report.contains("appendix-payload"));
        assertTrue(report.contains("technical-anchor"));
        assertTrue(report.contains("mixed-pdf"));
        assertTrue(report.contains("secondaryIndexCandidate"));

        var appendixCase = result.caseResults().stream()
                .filter(caseResult -> caseResult.caseId().equals("appendix-payload"))
                .findFirst()
                .orElseThrow();
        assertTrue(appendixCase.payloadHitRatioBaseline() > appendixCase.payloadHitRatioShadow());

        var technicalAnchorCase = result.caseResults().stream()
                .filter(caseResult -> caseResult.caseId().equals("technical-anchor"))
                .findFirst()
                .orElseThrow();
        assertTrue(technicalAnchorCase.expectedAnchorHitShadow());
        assertTrue(!technicalAnchorCase.protectedAnchorRegression());
    }
}
