package com.josh.interviewj.knowledgebase.preprocessing.evaluation;

import com.josh.interviewj.knowledgebase.preprocessing.evaluation.StructureAwareOfflineEvaluationTestSupport.EmbeddingTemplate;
import com.josh.interviewj.knowledgebase.preprocessing.evaluation.StructureAwareOfflineEvaluationTestSupport.TemplateResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Writes structure-aware chunking evaluation reports.
 */
public final class StructureAwareReportWriter {

    private StructureAwareReportWriter() {
    }

    public static Path writeReport(List<StructureAwareOfflineEvaluationTestSupport.CaseResult> caseResults) {
        try {
            Path reportsDir = Path.of("build/reports/structure-aware-evaluation");
            Files.createDirectories(reportsDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path reportPath = reportsDir.resolve("structure-aware-evaluation-" + timestamp + ".md");

            StringBuilder report = new StringBuilder();
            report.append("# Structure-Aware Chunking Evaluation Report\n\n");
            report.append("Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

            // Summary statistics
            report.append("## Summary\n\n");
            report.append("| Metric | Baseline | Shadow | Delta |\n");
            report.append("|--------|----------|--------|-------|\n");

            double avgBaselineChunks = caseResults.stream().mapToInt(StructureAwareOfflineEvaluationTestSupport.CaseResult::baselineChunkCount).average().orElse(0);
            double avgShadowChunks = caseResults.stream().mapToInt(StructureAwareOfflineEvaluationTestSupport.CaseResult::shadowChunkCount).average().orElse(0);
            report.append(String.format("| Avg Chunks/Doc | %.1f | %.1f | %+.1f |\n", avgBaselineChunks, avgShadowChunks, avgShadowChunks - avgBaselineChunks));

            long anchorHitBaseline = caseResults.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::anchorHitBaseline).count();
            long anchorHitShadow = caseResults.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::anchorHitShadow).count();
            report.append(String.format("| Anchor Hit@5 | %d/%d | %d/%d | %+d |\n",
                    anchorHitBaseline, caseResults.size(), anchorHitShadow, caseResults.size(), anchorHitShadow - anchorHitBaseline));

            long anchorRecallBaseline = caseResults.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::anchorRecallBaseline).count();
            long anchorRecallShadow = caseResults.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::anchorRecallShadow).count();
            report.append(String.format("| Anchor Recall@5 | %d/%d | %d/%d | %+d |\n",
                    anchorRecallBaseline, caseResults.size(), anchorRecallShadow, caseResults.size(), anchorRecallShadow - anchorRecallBaseline));

            long sectionHitBaseline = caseResults.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::sectionHintHitBaseline).count();
            long sectionHitShadow = caseResults.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::sectionHintHitShadow).count();
            report.append(String.format("| Section Hint Hit@5 | %d/%d | %d/%d | %+d |\n",
                    sectionHitBaseline, caseResults.size(), sectionHitShadow, caseResults.size(), sectionHitShadow - sectionHitBaseline));

            double avgParentContext = caseResults.stream().mapToDouble(StructureAwareOfflineEvaluationTestSupport.CaseResult::parentContextCoverage).average().orElse(0);
            double avgSectionHint = caseResults.stream().mapToDouble(StructureAwareOfflineEvaluationTestSupport.CaseResult::sectionHintCoverage).average().orElse(0);
            report.append(String.format("| Parent Context Coverage | - | %.1f%% | - |\n", avgParentContext * 100));
            report.append(String.format("| Section Hint Coverage | - | %.1f%% | - |\n", avgSectionHint * 100));

            // By query type
            report.append("\n## By Query Type\n\n");
            Map<String, List<StructureAwareOfflineEvaluationTestSupport.CaseResult>> byQueryType = caseResults.stream()
                    .collect(Collectors.groupingBy(StructureAwareOfflineEvaluationTestSupport.CaseResult::queryType, TreeMap::new, Collectors.toList()));

            report.append("| Query Type | Count | Anchor Hit@5 | Section Hit@5 |\n");
            report.append("|------------|-------|--------------|---------------|\n");
            for (Map.Entry<String, List<StructureAwareOfflineEvaluationTestSupport.CaseResult>> entry : byQueryType.entrySet()) {
                List<StructureAwareOfflineEvaluationTestSupport.CaseResult> cases = entry.getValue();
                long anchorHit = cases.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::anchorHitShadow).count();
                long sectionHit = cases.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::sectionHintHitShadow).count();
                report.append(String.format("| %s | %d | %d/%d | %d/%d |\n",
                        entry.getKey(), cases.size(), anchorHit, cases.size(), sectionHit, cases.size()));
            }

            // By source type
            report.append("\n## By Source Type\n\n");
            Map<String, List<StructureAwareOfflineEvaluationTestSupport.CaseResult>> bySourceType = caseResults.stream()
                    .collect(Collectors.groupingBy(StructureAwareOfflineEvaluationTestSupport.CaseResult::sourceType, TreeMap::new, Collectors.toList()));

            report.append("| Source Type | Count | Anchor Hit@5 | Section Hit@5 |\n");
            report.append("|-------------|-------|--------------|---------------|\n");
            for (Map.Entry<String, List<StructureAwareOfflineEvaluationTestSupport.CaseResult>> entry : bySourceType.entrySet()) {
                List<StructureAwareOfflineEvaluationTestSupport.CaseResult> cases = entry.getValue();
                long anchorHit = cases.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::anchorHitShadow).count();
                long sectionHit = cases.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::sectionHintHitShadow).count();
                report.append(String.format("| %s | %d | %d/%d | %d/%d |\n",
                        entry.getKey(), cases.size(), anchorHit, cases.size(), sectionHit, cases.size()));
            }

            // Detailed results
            report.append("\n## Detailed Results\n\n");
            report.append("| Case ID | Query Type | Source | Baseline Chunks | Shadow Chunks | Anchor Hit | Section Hit |\n");
            report.append("|---------|------------|--------|-----------------|---------------|------------|-------------|\n");
            for (StructureAwareOfflineEvaluationTestSupport.CaseResult result : caseResults) {
                report.append(String.format("| %s | %s | %s | %d | %d | %s | %s |\n",
                        result.caseId(),
                        result.queryType(),
                        result.sourceType(),
                        result.baselineChunkCount(),
                        result.shadowChunkCount(),
                        result.anchorHitShadow() ? "✓" : "✗",
                        result.sectionHintHitShadow() ? "✓" : "✗"));
            }

            // Template A/B Comparison
            report.append("\n## Embedding Template Comparison (A/B)\n\n");
            report.append("Comparing two embedding text templates for context injection:\n\n");
            report.append("- **Template A (Full Context)**: Document title + section path + block type\n");
            report.append("- **Template B (Minimal Context)**: Section path only\n\n");

            report.append("| Template | Hit@5 | Recall@5 | Avg Embedding Chars | Context Coverage |\n");
            report.append("|----------|-------|----------|---------------------|------------------|\n");

            for (EmbeddingTemplate template : EmbeddingTemplate.values()) {
                long templateHit = caseResults.stream()
                        .filter(r -> r.templateResults().containsKey(template))
                        .filter(r -> r.templateResults().get(template).anchorHit())
                        .count();
                long templateRecall = caseResults.stream()
                        .filter(r -> r.templateResults().containsKey(template))
                        .filter(r -> r.templateResults().get(template).anchorRecall())
                        .count();
                double avgEmbeddingChars = caseResults.stream()
                        .filter(r -> r.templateResults().containsKey(template))
                        .mapToInt(r -> r.templateResults().get(template).embeddingChars())
                        .average()
                        .orElse(0);
                double avgContextCoverage = caseResults.stream()
                        .filter(r -> r.templateResults().containsKey(template))
                        .mapToDouble(r -> r.templateResults().get(template).contextCoverage())
                        .average()
                        .orElse(0);

                report.append(String.format("| %s | %d/%d | %d/%d | %.0f | %.1f%% |\n",
                        template.getDisplayName(),
                        templateHit, caseResults.size(),
                        templateRecall, caseResults.size(),
                        avgEmbeddingChars,
                        avgContextCoverage * 100));
            }

            // Determine recommended template
            long templateAHit = caseResults.stream()
                    .filter(r -> r.templateResults().containsKey(EmbeddingTemplate.FULL_CONTEXT))
                    .filter(r -> r.templateResults().get(EmbeddingTemplate.FULL_CONTEXT).anchorHit())
                    .count();
            long templateBHit = caseResults.stream()
                    .filter(r -> r.templateResults().containsKey(EmbeddingTemplate.MINIMAL_CONTEXT))
                    .filter(r -> r.templateResults().get(EmbeddingTemplate.MINIMAL_CONTEXT).anchorHit())
                    .count();

            report.append("\n**Template Recommendation**: ");
            if (templateAHit >= templateBHit) {
                report.append("Template A (Full Context) - Provides better or equal retrieval with rich context.\n");
            } else {
                report.append("Template B (Minimal Context) - Simpler template with comparable retrieval.\n");
            }

            // Rollout recommendation
            report.append("\n## Rollout Recommendation\n\n");
            long totalCases = caseResults.size();
            long anchorHits = caseResults.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::anchorHitShadow).count();
            long anchorRecalls = caseResults.stream().filter(StructureAwareOfflineEvaluationTestSupport.CaseResult::anchorRecallShadow).count();
            long anchorHitRegressions = caseResults.stream()
                    .filter(r -> r.anchorHitBaseline() && !r.anchorHitShadow())
                    .count();
            long anchorRecallRegressions = caseResults.stream()
                    .filter(r -> r.anchorRecallBaseline() && !r.anchorRecallShadow())
                    .count();
            long sectionImprovements = caseResults.stream()
                    .filter(r -> !r.sectionHintHitBaseline() && r.sectionHintHitShadow())
                    .count();

            report.append(String.format("- Hit@5 regressions: %d\n", anchorHitRegressions));
            report.append(String.format("- Recall@5 regressions: %d\n", anchorRecallRegressions));
            report.append(String.format("- Section hint improvements: %d\n", sectionImprovements));

            if (anchorHitRegressions == 0 && anchorRecallRegressions == 0 && anchorHits >= totalCases * 0.8) {
                report.append("\n**Recommendation: PROCEED** - No regressions, high hit rate.\n");
            } else if (anchorHitRegressions <= 1 && anchorRecallRegressions <= 1 && anchorHits >= totalCases * 0.7) {
                report.append("\n**Recommendation: PROCEED WITH CAUTION** - Minor regressions, acceptable hit rate.\n");
            } else {
                report.append("\n**Recommendation: DO NOT PROCEED** - Significant regressions detected.\n");
            }

            Files.writeString(reportPath, report.toString());
            return reportPath;
        } catch (IOException ex) {
            throw new RuntimeException("Failed to write evaluation report", ex);
        }
    }
}