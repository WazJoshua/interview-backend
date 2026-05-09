package com.josh.interviewj.ragqa.evaluation;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.josh.interviewj.ragqa.model.FusedRetrievalResult;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievedChunk;
import com.josh.interviewj.ragqa.service.RetrievalResultFusionService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrievalOfflineEvaluationTest {

    private static final Path ROOT = Path.of("src/test/resources/ragqa/evaluation/hybrid-retrieval");
    private static final Path REPORT = Path.of("docs/issues/2026-03-22-hybrid-retrieval-phase1-offline-evaluation-baseline.md");
    private static final Path DESIGN_DOC = Path.of("docs/rag-qa/optimization/06-hybrid-retrieval-and-literal-recall-design.md");

    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final RetrievalResultFusionService fusionService = new RetrievalResultFusionService();

    @Test
    void evaluate_HybridRetrievalPhase1_ProducesBucketedBaselineReport() throws Exception {
        Manifest manifest = yamlMapper.readValue(ROOT.resolve("manifest.yaml").toFile(), Manifest.class);
        List<EvaluationCase> cases = new ArrayList<>();
        for (String fixture : manifest.getFixtures()) {
            cases.add(yamlMapper.readValue(ROOT.resolve(fixture).toFile(), EvaluationCase.class));
        }

        Map<String, List<String>> denseSelections = new LinkedHashMap<>();
        Map<String, List<String>> sparseSelections = new LinkedHashMap<>();
        Map<String, List<String>> hybridSelections = new LinkedHashMap<>();
        Map<String, FusedRetrievalResult> hybridResults = new LinkedHashMap<>();

        for (EvaluationCase evaluationCase : cases) {
            denseSelections.put(evaluationCase.getCaseId(), selectTopKeys(evaluationCase.getDenseBranchRanks(), evaluationCase.getFinalTopK()));
            sparseSelections.put(evaluationCase.getCaseId(), selectTopKeys(evaluationCase.getSparseBranchRanks(), evaluationCase.getFinalTopK()));
            FusedRetrievalResult hybridResult = fuse(evaluationCase);
            hybridResults.put(evaluationCase.getCaseId(), hybridResult);
            hybridSelections.put(
                    evaluationCase.getCaseId(),
                    hybridResult.selectedChunks().stream().map(chunk -> chunk.content()).toList()
            );
        }

        Summary denseSummary = summarize(cases, denseSelections, Map.of(), denseSelections, sparseSelections);
        Summary hybridSummary = summarize(cases, hybridSelections, hybridResults, denseSelections, sparseSelections);
        Map<String, Summary> denseByBucket = summarizeByBucket(cases, denseSelections, Map.of(), denseSelections, sparseSelections);
        Map<String, Summary> hybridByBucket = summarizeByBucket(cases, hybridSelections, hybridResults, denseSelections, sparseSelections);

        Files.createDirectories(REPORT.getParent());
        Files.writeString(
                REPORT,
                renderReport(cases, denseSelections, sparseSelections, hybridSelections, hybridResults, denseSummary, hybridSummary, denseByBucket, hybridByBucket),
                StandardCharsets.UTF_8
        );

        assertThat(REPORT).exists();
        assertThat(Files.readString(REPORT)).contains("Hit Rate@K", "Recall@K", "sparse-only rescue count", "cross-branch mismatch count");
        assertThat(Files.readString(DESIGN_DOC)).contains("Implementation Handoff Note");

        for (EvaluationCase evaluationCase : cases) {
            assertThat(denseSelections.get(evaluationCase.getCaseId()))
                    .as("dense top keys for %s", evaluationCase.getCaseId())
                    .containsExactlyElementsOf(evaluationCase.getDenseExpectedTopKeys());
            assertThat(sparseSelections.get(evaluationCase.getCaseId()))
                    .as("sparse top keys for %s", evaluationCase.getCaseId())
                    .containsExactlyElementsOf(evaluationCase.getSparseExpectedTopKeys());
            assertThat(hybridSelections.get(evaluationCase.getCaseId()))
                    .as("hybrid top keys for %s", evaluationCase.getCaseId())
                    .containsExactlyElementsOf(evaluationCase.getFusedExpectedTopKeys());

            FusedRetrievalResult hybridResult = hybridResults.get(evaluationCase.getCaseId());
            assertThat(hybridResult.sparseOnlyRescueCount() > 0).isEqualTo(evaluationCase.isExpectSparseOnlyRescue());
            assertThat(hybridResult.crossBranchMismatchCount() > 0).isEqualTo(evaluationCase.isExpectCrossBranchMismatch());
        }
    }

    @Test
    void summarize_UsesRuntimeSelectionsForOverlapMetrics() throws Exception {
        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setCaseId("overlap-metric-runtime");
        evaluationCase.setRelevantChunkKeys(List.of("doc-a#0"));
        evaluationCase.setDenseExpectedTopKeys(List.of("expected-overlap#0"));
        evaluationCase.setSparseExpectedTopKeys(List.of("expected-overlap#0"));

        Map<String, List<String>> selections = Map.of("overlap-metric-runtime", List.of("doc-a#0"));
        Map<String, List<String>> denseSelections = Map.of("overlap-metric-runtime", List.of("dense-only#0"));
        Map<String, List<String>> sparseSelections = Map.of("overlap-metric-runtime", List.of("sparse-only#0"));

        Method summarize = HybridRetrievalOfflineEvaluationTest.class.getDeclaredMethod(
                "summarize",
                List.class,
                Map.class,
                Map.class,
                Map.class,
                Map.class
        );
        summarize.setAccessible(true);
        Object summary = summarize.invoke(this, List.of(evaluationCase), selections, Map.of(), denseSelections, sparseSelections);

        Method overlapRatio = summary.getClass().getDeclaredMethod("overlapRatio");
        Method duplicateRatio = summary.getClass().getDeclaredMethod("duplicateRatio");

        assertThat((double) overlapRatio.invoke(summary)).isZero();
        assertThat((double) duplicateRatio.invoke(summary)).isZero();
    }

    private FusedRetrievalResult fuse(EvaluationCase evaluationCase) {
        List<RetrievedChunk> candidates = new ArrayList<>();
        evaluationCase.getDenseBranchRanks().forEach((key, rank) ->
                candidates.add(toChunk(evaluationCase, QueryVariant.ORIGINAL, RetrievalMode.DENSE, key, rank))
        );
        evaluationCase.getSparseBranchRanks().forEach((key, rank) ->
                candidates.add(toChunk(evaluationCase, QueryVariant.ORIGINAL, RetrievalMode.SPARSE, key, rank))
        );
        return fusionService.fuse(candidates, evaluationCase.getFinalTopK());
    }

    private RetrievedChunk toChunk(
            EvaluationCase evaluationCase,
            QueryVariant queryVariant,
            RetrievalMode retrievalMode,
            String key,
            int rank
    ) {
        String[] parts = key.split("#");
        String documentKey = parts[0];
        int chunkIndex = Integer.parseInt(parts[1]);
        long documentId = toDocumentOrder(documentKey);
        double similarity = resolveScore(evaluationCase, retrievalMode, key, rank);
        return new RetrievedChunk(
                queryVariant,
                retrievalMode,
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                documentId,
                documentKey,
                chunkIndex,
                key,
                similarity,
                rank
        );
    }

    private double resolveScore(EvaluationCase evaluationCase, RetrievalMode retrievalMode, String key, int rank) {
        Map<String, Double> scores = retrievalMode == RetrievalMode.DENSE
                ? evaluationCase.getDenseBranchScores()
                : evaluationCase.getSparseBranchScores();
        return scores.getOrDefault(key, 1D - (rank * 0.01D));
    }

    private long toDocumentOrder(String documentKey) {
        long value = 0L;
        for (int index = 0; index < Math.min(documentKey.length(), 8); index++) {
            value = (value * 131) + documentKey.charAt(index);
        }
        return value;
    }

    private List<String> selectTopKeys(Map<String, Integer> ranks, int topK) {
        return ranks.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Summary summarize(
            List<EvaluationCase> cases,
            Map<String, List<String>> selections,
            Map<String, FusedRetrievalResult> hybridResults,
            Map<String, List<String>> denseSelections,
            Map<String, List<String>> sparseSelections
    ) {
        int hits = 0;
        int recalled = 0;
        int relevantTotal = 0;
        int selectedTotal = 0;
        int overlapCount = 0;
        int unionCount = 0;
        int sparseOnlyRescueCount = 0;
        int crossBranchMismatchCount = 0;

        for (EvaluationCase evaluationCase : cases) {
            List<String> selected = selections.get(evaluationCase.getCaseId());
            List<String> relevant = evaluationCase.getRelevantChunkKeys();
            relevantTotal += relevant.size();
            selectedTotal += Math.max(1, selected.size());
            long relevantHitCount = selected.stream().filter(relevant::contains).count();
            if (relevantHitCount > 0) {
                hits++;
            }
            recalled += (int) relevantHitCount;

            List<String> denseTop = denseSelections.getOrDefault(evaluationCase.getCaseId(), List.of());
            List<String> sparseTop = sparseSelections.getOrDefault(evaluationCase.getCaseId(), List.of());
            int intersection = (int) denseTop.stream().filter(new LinkedHashSet<>(sparseTop)::contains).count();
            overlapCount += intersection;
            unionCount += new LinkedHashSet<String>() {{
                addAll(denseTop);
                addAll(sparseTop);
            }}.size();

            FusedRetrievalResult hybridResult = hybridResults.get(evaluationCase.getCaseId());
            if (hybridResult != null) {
                sparseOnlyRescueCount += hybridResult.sparseOnlyRescueCount();
                crossBranchMismatchCount += hybridResult.crossBranchMismatchCount();
            }
        }

        double totalCases = cases.isEmpty() ? 1D : cases.size();
        return new Summary(
                hits / totalCases,
                recalled / (double) Math.max(1, relevantTotal),
                recalled / (double) Math.max(1, selectedTotal),
                overlapCount / (double) Math.max(1, unionCount),
                overlapCount / (double) Math.max(1, selectedTotal),
                sparseOnlyRescueCount,
                crossBranchMismatchCount
        );
    }

    private Map<String, Summary> summarizeByBucket(
            List<EvaluationCase> cases,
            Map<String, List<String>> selections,
            Map<String, FusedRetrievalResult> hybridResults,
            Map<String, List<String>> denseSelections,
            Map<String, List<String>> sparseSelections
    ) {
        Map<String, List<EvaluationCase>> byBucket = new LinkedHashMap<>();
        for (EvaluationCase evaluationCase : cases) {
            for (String bucket : evaluationCase.getBucketTags()) {
                byBucket.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(evaluationCase);
            }
        }

        Map<String, Summary> summaries = new LinkedHashMap<>();
        byBucket.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> summaries.put(
                        entry.getKey(),
                        summarize(entry.getValue(), selections, hybridResults, denseSelections, sparseSelections)
                ));
        return summaries;
    }

    private String renderReport(
            List<EvaluationCase> cases,
            Map<String, List<String>> denseSelections,
            Map<String, List<String>> sparseSelections,
            Map<String, List<String>> hybridSelections,
            Map<String, FusedRetrievalResult> hybridResults,
            Summary denseSummary,
            Summary hybridSummary,
            Map<String, Summary> denseByBucket,
            Map<String, Summary> hybridByBucket
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Hybrid Retrieval Phase 1 Offline Evaluation Baseline\n\n");
        builder.append("Cases: ").append(cases.size()).append("\n\n");
        builder.append("## Overall\n\n");
        builder.append("| Variant | Hit Rate@K | Recall@K | precision proxy | overlap ratio | duplicate ratio | sparse-only rescue count | cross-branch mismatch count |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        appendSummaryRow(builder, "Dense baseline", denseSummary);
        appendSummaryRow(builder, "Hybrid fused", hybridSummary);

        builder.append("\n## Bucket Summary\n\n");
        builder.append("| Bucket | Cases | Dense Hit Rate@K | Hybrid Hit Rate@K | Dense Recall@K | Hybrid Recall@K | Dense Precision | Hybrid Precision | Rescue Count | Mismatch Count | Representative Note |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");

        LinkedHashSet<String> orderedBuckets = new LinkedHashSet<>();
        orderedBuckets.addAll(denseByBucket.keySet());
        orderedBuckets.addAll(hybridByBucket.keySet());
        orderedBuckets.stream().sorted(Comparator.naturalOrder()).forEach(bucket -> {
            Summary dense = denseByBucket.get(bucket);
            Summary hybrid = hybridByBucket.get(bucket);
            List<EvaluationCase> bucketCases = cases.stream()
                    .filter(caseItem -> caseItem.getBucketTags().contains(bucket))
                    .toList();
            builder.append("| ")
                    .append(bucket)
                    .append(" | ")
                    .append(bucketCases.size())
                    .append(" | ")
                    .append(formatPercent(dense == null ? 0D : dense.hitRate()))
                    .append(" | ")
                    .append(formatPercent(hybrid == null ? 0D : hybrid.hitRate()))
                    .append(" | ")
                    .append(formatPercent(dense == null ? 0D : dense.recall()))
                    .append(" | ")
                    .append(formatPercent(hybrid == null ? 0D : hybrid.recall()))
                    .append(" | ")
                    .append(formatPercent(dense == null ? 0D : dense.precisionProxy()))
                    .append(" | ")
                    .append(formatPercent(hybrid == null ? 0D : hybrid.precisionProxy()))
                    .append(" | ")
                    .append(hybrid == null ? 0 : hybrid.sparseOnlyRescueCount())
                    .append(" | ")
                    .append(hybrid == null ? 0 : hybrid.crossBranchMismatchCount())
                    .append(" | ")
                    .append(representativeNote(bucketCases, denseSelections, sparseSelections, hybridSelections, hybridResults))
                    .append(" |\n");
        });

        builder.append("\n## Case Expectations\n\n");
        for (EvaluationCase evaluationCase : cases) {
            builder.append("### ").append(evaluationCase.getCaseId()).append("\n");
            builder.append("- raw query: `").append(evaluationCase.getRawQuery()).append("`\n");
            builder.append("- buckets: ").append(String.join(", ", evaluationCase.getBucketTags())).append("\n");
            builder.append("- dense topK: ").append(denseSelections.get(evaluationCase.getCaseId())).append("\n");
            builder.append("- sparse topK: ").append(sparseSelections.get(evaluationCase.getCaseId())).append("\n");
            builder.append("- hybrid topK: ").append(hybridSelections.get(evaluationCase.getCaseId())).append("\n");
            builder.append("- sparse-only rescue count: ").append(hybridResults.get(evaluationCase.getCaseId()).sparseOnlyRescueCount()).append("\n");
            builder.append("- cross-branch mismatch count: ").append(hybridResults.get(evaluationCase.getCaseId()).crossBranchMismatchCount()).append("\n\n");
        }
        return builder.toString();
    }

    private void appendSummaryRow(StringBuilder builder, String label, Summary summary) {
        builder.append("| ")
                .append(label)
                .append(" | ")
                .append(formatPercent(summary.hitRate()))
                .append(" | ")
                .append(formatPercent(summary.recall()))
                .append(" | ")
                .append(formatPercent(summary.precisionProxy()))
                .append(" | ")
                .append(formatPercent(summary.overlapRatio()))
                .append(" | ")
                .append(formatPercent(summary.duplicateRatio()))
                .append(" | ")
                .append(summary.sparseOnlyRescueCount())
                .append(" | ")
                .append(summary.crossBranchMismatchCount())
                .append(" |\n");
    }

    private String representativeNote(
            List<EvaluationCase> bucketCases,
            Map<String, List<String>> denseSelections,
            Map<String, List<String>> sparseSelections,
            Map<String, List<String>> hybridSelections,
            Map<String, FusedRetrievalResult> hybridResults
    ) {
        return bucketCases.stream()
                .map(caseItem -> {
                    FusedRetrievalResult hybridResult = hybridResults.get(caseItem.getCaseId());
                    if (hybridResult.crossBranchMismatchCount() > 0) {
                        return caseItem.getCaseId() + ": mismatch observed";
                    }
                    if (hybridResult.sparseOnlyRescueCount() > 0) {
                        return caseItem.getCaseId() + ": sparse rescue " + hybridSelections.get(caseItem.getCaseId());
                    }
                    if (!denseSelections.get(caseItem.getCaseId()).equals(hybridSelections.get(caseItem.getCaseId()))) {
                        return caseItem.getCaseId() + ": dense " + denseSelections.get(caseItem.getCaseId()) + " -> hybrid " + hybridSelections.get(caseItem.getCaseId());
                    }
                    return caseItem.getCaseId() + ": stable overlap with sparse " + sparseSelections.get(caseItem.getCaseId());
                })
                .findFirst()
                .orElse("n/a");
    }

    private String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100D);
    }

    public static class Manifest {
        private List<String> fixtures = List.of();

        public List<String> getFixtures() {
            return fixtures;
        }

        public void setFixtures(List<String> fixtures) {
            this.fixtures = fixtures;
        }
    }

    public static class EvaluationCase {
        private String caseId;
        private String rawQuery;
        private List<String> bucketTags = List.of();
        private int finalTopK;
        private List<String> relevantChunkKeys = List.of();
        private List<String> denseExpectedTopKeys = List.of();
        private List<String> sparseExpectedTopKeys = List.of();
        private List<String> fusedExpectedTopKeys = List.of();
        private boolean expectSparseOnlyRescue;
        private boolean expectCrossBranchMismatch;
        private Map<String, Integer> denseBranchRanks = Map.of();
        private Map<String, Integer> sparseBranchRanks = Map.of();
        private Map<String, Double> denseBranchScores = Map.of();
        private Map<String, Double> sparseBranchScores = Map.of();

        public String getCaseId() { return caseId; }
        public void setCaseId(String caseId) { this.caseId = caseId; }
        public String getRawQuery() { return rawQuery; }
        public void setRawQuery(String rawQuery) { this.rawQuery = rawQuery; }
        public List<String> getBucketTags() { return bucketTags; }
        public void setBucketTags(List<String> bucketTags) { this.bucketTags = bucketTags; }
        public int getFinalTopK() { return finalTopK; }
        public void setFinalTopK(int finalTopK) { this.finalTopK = finalTopK; }
        public List<String> getRelevantChunkKeys() { return relevantChunkKeys; }
        public void setRelevantChunkKeys(List<String> relevantChunkKeys) { this.relevantChunkKeys = relevantChunkKeys; }
        public List<String> getDenseExpectedTopKeys() { return denseExpectedTopKeys; }
        public void setDenseExpectedTopKeys(List<String> denseExpectedTopKeys) { this.denseExpectedTopKeys = denseExpectedTopKeys; }
        public List<String> getSparseExpectedTopKeys() { return sparseExpectedTopKeys; }
        public void setSparseExpectedTopKeys(List<String> sparseExpectedTopKeys) { this.sparseExpectedTopKeys = sparseExpectedTopKeys; }
        public List<String> getFusedExpectedTopKeys() { return fusedExpectedTopKeys; }
        public void setFusedExpectedTopKeys(List<String> fusedExpectedTopKeys) { this.fusedExpectedTopKeys = fusedExpectedTopKeys; }
        public boolean isExpectSparseOnlyRescue() { return expectSparseOnlyRescue; }
        public void setExpectSparseOnlyRescue(boolean expectSparseOnlyRescue) { this.expectSparseOnlyRescue = expectSparseOnlyRescue; }
        public boolean isExpectCrossBranchMismatch() { return expectCrossBranchMismatch; }
        public void setExpectCrossBranchMismatch(boolean expectCrossBranchMismatch) { this.expectCrossBranchMismatch = expectCrossBranchMismatch; }
        public Map<String, Integer> getDenseBranchRanks() { return denseBranchRanks; }
        public void setDenseBranchRanks(Map<String, Integer> denseBranchRanks) { this.denseBranchRanks = denseBranchRanks; }
        public Map<String, Integer> getSparseBranchRanks() { return sparseBranchRanks; }
        public void setSparseBranchRanks(Map<String, Integer> sparseBranchRanks) { this.sparseBranchRanks = sparseBranchRanks; }
        public Map<String, Double> getDenseBranchScores() { return denseBranchScores; }
        public void setDenseBranchScores(Map<String, Double> denseBranchScores) { this.denseBranchScores = denseBranchScores; }
        public Map<String, Double> getSparseBranchScores() { return sparseBranchScores; }
        public void setSparseBranchScores(Map<String, Double> sparseBranchScores) { this.sparseBranchScores = sparseBranchScores; }
    }

    private record Summary(
            double hitRate,
            double recall,
            double precisionProxy,
            double overlapRatio,
            double duplicateRatio,
            int sparseOnlyRescueCount,
            int crossBranchMismatchCount
    ) {
    }
}
