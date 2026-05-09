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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryUnderstandingOfflineEvaluationTest {

    private static final Path ROOT = Path.of("src/test/resources/knowledgebase/evaluation/query-understanding");
    private static final Path REPORT = Path.of("build/reports/tests/query-understanding-offline-report.md");

    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final RetrievalResultFusionService fusionService = new RetrievalResultFusionService();

    @Test
    void evaluate_QueryUnderstandingPhases_ProducesBucketedMetricsReport() throws Exception {
        Manifest manifest = yamlMapper.readValue(ROOT.resolve("manifest.yaml").toFile(), Manifest.class);
        List<EvaluationCase> cases = new ArrayList<>();
        for (String fixture : manifest.getFixtures()) {
            cases.add(yamlMapper.readValue(ROOT.resolve(fixture).toFile(), EvaluationCase.class));
        }

        Map<String, List<String>> phaseSelections = new LinkedHashMap<>();
        for (EvaluationCase evaluationCase : cases) {
            phaseSelections.put(evaluationCase.getCaseId() + "|phase1", selectTopKeys(evaluationCase.getOriginalBranchRanks(), evaluationCase.getFinalTopK()));
            phaseSelections.put(evaluationCase.getCaseId() + "|phase2", selectTopKeys(
                    evaluationCase.getRewriteCandidate() == null || evaluationCase.getRewriteCandidate().isBlank()
                            ? evaluationCase.getOriginalBranchRanks()
                            : evaluationCase.getRewriteBranchRanks(),
                    evaluationCase.getFinalTopK()
            ));
            phaseSelections.put(evaluationCase.getCaseId() + "|phase3", selectPhase3Keys(evaluationCase));
        }

        Summary phase1 = summarize(cases, phaseSelections, "phase1", null);
        Summary phase2 = summarize(cases, phaseSelections, "phase2", "phase1");
        Summary phase3 = summarize(cases, phaseSelections, "phase3", "phase1");

        Map<String, Summary> bucketPhase1 = summarizeByBucket(cases, phaseSelections, "phase1", null);
        Map<String, Summary> bucketPhase2 = summarizeByBucket(cases, phaseSelections, "phase2", "phase1");
        Map<String, Summary> bucketPhase3 = summarizeByBucket(cases, phaseSelections, "phase3", "phase1");

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, renderReport(cases, phase1, phase2, phase3, bucketPhase1, bucketPhase2, bucketPhase3), StandardCharsets.UTF_8);

        assertThat(REPORT).exists();
        assertThat(Files.readString(REPORT)).contains("Hit Rate@K", "Recall@K", "rewrite improvement rate");
        for (EvaluationCase evaluationCase : cases) {
            assertThat(phaseSelections.get(evaluationCase.getCaseId() + "|phase2"))
                    .as("phase2 top keys for %s", evaluationCase.getCaseId())
                    .containsExactlyElementsOf(evaluationCase.getExpectedPhase2TopKeys());
            assertThat(phaseSelections.get(evaluationCase.getCaseId() + "|phase3"))
                    .as("phase3 top keys for %s", evaluationCase.getCaseId())
                    .containsExactlyElementsOf(evaluationCase.getExpectedPhase3TopKeys());
        }
    }

    private List<String> selectPhase3Keys(EvaluationCase evaluationCase) {
        List<RetrievedChunk> candidates = new ArrayList<>();
        evaluationCase.getOriginalBranchRanks().forEach((key, rank) -> candidates.add(toChunk(evaluationCase, QueryVariant.ORIGINAL, key, rank)));
        evaluationCase.getRewriteBranchRanks().forEach((key, rank) -> candidates.add(toChunk(evaluationCase, QueryVariant.REWRITE, key, rank)));
        FusedRetrievalResult result = fusionService.fuse(candidates, evaluationCase.getFinalTopK());
        return result.selectedChunks().stream().map(chunk -> chunk.content()).toList();
    }

    private RetrievedChunk toChunk(EvaluationCase evaluationCase, QueryVariant queryVariant, String key, int rank) {
        String[] parts = key.split("#");
        String documentKey = parts[0];
        int chunkIndex = Integer.parseInt(parts[1]);
        long documentId = toDocumentOrder(documentKey);
        double similarity = resolveSimilarity(evaluationCase, queryVariant, key, rank);
        return new RetrievedChunk(
                queryVariant,
                RetrievalMode.DENSE,
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                documentId,
                documentKey,
                chunkIndex,
                key,
                similarity,
                rank
        );
    }

    private double resolveSimilarity(EvaluationCase evaluationCase, QueryVariant queryVariant, String key, int rank) {
        Map<String, Double> scores = queryVariant == QueryVariant.ORIGINAL
                ? evaluationCase.getOriginalBranchScores()
                : evaluationCase.getRewriteBranchScores();
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
            Map<String, List<String>> phaseSelections,
            String phase,
            String baselinePhase
    ) {
        int hits = 0;
        int recalled = 0;
        int relevantTotal = 0;
        int noResults = 0;
        int improvements = 0;

        for (EvaluationCase evaluationCase : cases) {
            List<String> selected = phaseSelections.get(evaluationCase.getCaseId() + "|" + phase);
            List<String> relevant = evaluationCase.getRelevantChunkKeys();
            relevantTotal += relevant.size();
            long intersectionCount = selected.stream().filter(relevant::contains).count();
            if (intersectionCount > 0) {
                hits++;
            }
            if (selected.isEmpty()) {
                noResults++;
            }
            recalled += (int) intersectionCount;
            if (baselinePhase != null) {
                List<String> baseline = phaseSelections.get(evaluationCase.getCaseId() + "|" + baselinePhase);
                long baselineHits = baseline.stream().filter(relevant::contains).count();
                if (intersectionCount > baselineHits) {
                    improvements++;
                }
            }
        }

        double totalCases = cases.isEmpty() ? 1D : cases.size();
        return new Summary(
                hits / totalCases,
                recalled / (double) Math.max(1, relevantTotal),
                noResults / totalCases,
                baselinePhase == null ? 0D : improvements / totalCases
        );
    }

    private Map<String, Summary> summarizeByBucket(
            List<EvaluationCase> cases,
            Map<String, List<String>> phaseSelections,
            String phase,
            String baselinePhase
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
                .forEach(entry -> summaries.put(entry.getKey(), summarize(entry.getValue(), phaseSelections, phase, baselinePhase)));
        return summaries;
    }

    private String renderReport(
            List<EvaluationCase> cases,
            Summary phase1,
            Summary phase2,
            Summary phase3,
            Map<String, Summary> bucketPhase1,
            Map<String, Summary> bucketPhase2,
            Map<String, Summary> bucketPhase3
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Query Understanding Offline Evaluation\n\n");
        builder.append("Cases: ").append(cases.size()).append("\n\n");
        builder.append("## Overall\n\n");
        builder.append("| Phase | Hit Rate@K | Recall@K | No-result rate | rewrite improvement rate |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: |\n");
        appendSummaryRow(builder, "Phase 1 baseline", phase1);
        appendSummaryRow(builder, "Phase 2 rewrite-single", phase2);
        appendSummaryRow(builder, "Phase 3 dual-branch", phase3);
        builder.append("\n## Buckets\n\n");
        builder.append("| Bucket | Phase 1 Hit Rate@K | Phase 2 Hit Rate@K | Phase 3 Hit Rate@K |\n");
        builder.append("| --- | ---: | ---: | ---: |\n");

        LinkedHashSet<String> orderedBuckets = new LinkedHashSet<>();
        orderedBuckets.addAll(bucketPhase1.keySet());
        orderedBuckets.addAll(bucketPhase2.keySet());
        orderedBuckets.addAll(bucketPhase3.keySet());
        orderedBuckets.stream().sorted(Comparator.naturalOrder()).forEach(bucket -> {
            Summary p1 = bucketPhase1.get(bucket);
            Summary p2 = bucketPhase2.get(bucket);
            Summary p3 = bucketPhase3.get(bucket);
            builder.append("| ")
                    .append(bucket)
                    .append(" | ")
                    .append(formatPercent(p1 == null ? 0D : p1.hitRate()))
                    .append(" | ")
                    .append(formatPercent(p2 == null ? 0D : p2.hitRate()))
                    .append(" | ")
                    .append(formatPercent(p3 == null ? 0D : p3.hitRate()))
                    .append(" |\n");
        });
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
                .append(formatPercent(summary.noResultRate()))
                .append(" | ")
                .append(formatPercent(summary.improvementRate()))
                .append(" |\n");
    }

    private String formatPercent(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100D);
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
        private String expectedNormalizedQuery;
        private String rewriteCandidate;
        private List<String> bucketTags = List.of();
        private int finalTopK;
        private List<String> relevantChunkKeys = List.of();
        private List<String> expectedPhase2TopKeys = List.of();
        private List<String> expectedPhase3TopKeys = List.of();
        private Map<String, Integer> originalBranchRanks = Map.of();
        private Map<String, Integer> rewriteBranchRanks = Map.of();
        private Map<String, Double> originalBranchScores = Map.of();
        private Map<String, Double> rewriteBranchScores = Map.of();

        public String getCaseId() {
            return caseId;
        }

        public void setCaseId(String caseId) {
            this.caseId = caseId;
        }

        public String getRawQuery() {
            return rawQuery;
        }

        public void setRawQuery(String rawQuery) {
            this.rawQuery = rawQuery;
        }

        public String getExpectedNormalizedQuery() {
            return expectedNormalizedQuery;
        }

        public void setExpectedNormalizedQuery(String expectedNormalizedQuery) {
            this.expectedNormalizedQuery = expectedNormalizedQuery;
        }

        public String getRewriteCandidate() {
            return rewriteCandidate;
        }

        public void setRewriteCandidate(String rewriteCandidate) {
            this.rewriteCandidate = rewriteCandidate;
        }

        public List<String> getBucketTags() {
            return bucketTags;
        }

        public void setBucketTags(List<String> bucketTags) {
            this.bucketTags = bucketTags;
        }

        public int getFinalTopK() {
            return finalTopK;
        }

        public void setFinalTopK(int finalTopK) {
            this.finalTopK = finalTopK;
        }

        public List<String> getRelevantChunkKeys() {
            return relevantChunkKeys;
        }

        public void setRelevantChunkKeys(List<String> relevantChunkKeys) {
            this.relevantChunkKeys = relevantChunkKeys;
        }

        public List<String> getExpectedPhase2TopKeys() {
            return expectedPhase2TopKeys;
        }

        public void setExpectedPhase2TopKeys(List<String> expectedPhase2TopKeys) {
            this.expectedPhase2TopKeys = expectedPhase2TopKeys;
        }

        public List<String> getExpectedPhase3TopKeys() {
            return expectedPhase3TopKeys;
        }

        public void setExpectedPhase3TopKeys(List<String> expectedPhase3TopKeys) {
            this.expectedPhase3TopKeys = expectedPhase3TopKeys;
        }

        public Map<String, Integer> getOriginalBranchRanks() {
            return originalBranchRanks;
        }

        public void setOriginalBranchRanks(Map<String, Integer> originalBranchRanks) {
            this.originalBranchRanks = originalBranchRanks;
        }

        public Map<String, Integer> getRewriteBranchRanks() {
            return rewriteBranchRanks;
        }

        public void setRewriteBranchRanks(Map<String, Integer> rewriteBranchRanks) {
            this.rewriteBranchRanks = rewriteBranchRanks;
        }

        public Map<String, Double> getOriginalBranchScores() {
            return originalBranchScores;
        }

        public void setOriginalBranchScores(Map<String, Double> originalBranchScores) {
            this.originalBranchScores = originalBranchScores;
        }

        public Map<String, Double> getRewriteBranchScores() {
            return rewriteBranchScores;
        }

        public void setRewriteBranchScores(Map<String, Double> rewriteBranchScores) {
            this.rewriteBranchScores = rewriteBranchScores;
        }
    }

    private record Summary(
            double hitRate,
            double recall,
            double noResultRate,
            double improvementRate
    ) {
    }
}
