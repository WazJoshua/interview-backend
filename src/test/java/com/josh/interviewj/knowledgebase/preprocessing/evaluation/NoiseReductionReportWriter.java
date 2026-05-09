package com.josh.interviewj.knowledgebase.preprocessing.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class NoiseReductionReportWriter {

    private NoiseReductionReportWriter() {
    }

    public static Path writeReport(List<NoiseReductionOfflineEvaluationTestSupport.CaseResult> caseResults) {
        try {
            Path reportDirectory = Path.of("build", "reports", "knowledgebase", "noise-reduction");
            Files.createDirectories(reportDirectory);
            Path reportPath = reportDirectory.resolve("shadow-report.md");
            StringBuilder report = new StringBuilder();
            report.append("# Noise Reduction Shadow Report\n\n");
            for (NoiseReductionOfflineEvaluationTestSupport.CaseResult caseResult : caseResults) {
                report.append("## ").append(caseResult.caseId()).append("\n\n");
                report.append("- fixture: ").append(caseResult.fixture()).append("\n");
                report.append("- query: ").append(caseResult.query()).append("\n");
                report.append("- baselineTopK: ").append(caseResult.baselineTopChunkIds()).append("\n");
                report.append("- shadowTopK: ").append(caseResult.shadowTopChunkIds()).append("\n");
                report.append("- expectedAnchorHitBaseline: ").append(caseResult.expectedAnchorHitBaseline()).append("\n");
                report.append("- expectedAnchorHitShadow: ").append(caseResult.expectedAnchorHitShadow()).append("\n");
                report.append("- payloadHitRatioBaseline: ").append(caseResult.payloadHitRatioBaseline()).append("\n");
                report.append("- payloadHitRatioShadow: ").append(caseResult.payloadHitRatioShadow()).append("\n");
                report.append("- protectedAnchorRegression: ").append(caseResult.protectedAnchorRegression()).append("\n");
                report.append("- secondaryIndexCandidateRatio: ").append(caseResult.secondaryIndexCandidateRatio()).append("\n");
                report.append("- secondaryIndexCandidate: ").append(caseResult.secondaryIndexCandidateChunkIds()).append("\n\n");
            }
            Files.writeString(reportPath, report.toString());
            return reportPath;
        } catch (IOException ex) {
            throw new RuntimeException("Failed to write shadow report", ex);
        }
    }
}
