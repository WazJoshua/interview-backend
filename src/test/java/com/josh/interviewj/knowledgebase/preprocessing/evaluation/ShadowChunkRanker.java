package com.josh.interviewj.knowledgebase.preprocessing.evaluation;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class ShadowChunkRanker {

    private ShadowChunkRanker() {
    }

    public static List<NoiseReductionOfflineEvaluationTestSupport.EvaluatedChunk> rank(
            String query,
            List<NoiseReductionOfflineEvaluationTestSupport.EvaluatedChunk> chunks,
            boolean shadowFiltered
    ) {
        Set<String> queryTerms = tokenize(query);
        return chunks.stream()
                .filter(chunk -> !shadowFiltered || !chunk.secondaryIndexCandidate())
                .map(chunk -> chunk.withScore(score(queryTerms, chunk)))
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .toList();
    }

    private static double score(Set<String> queryTerms, NoiseReductionOfflineEvaluationTestSupport.EvaluatedChunk chunk) {
        Set<String> chunkTerms = tokenize(chunk.content());
        long overlap = queryTerms.stream().filter(chunkTerms::contains).count();
        double protectedBonus = chunk.hasProtectedAnchor() ? 0.5D : 0.0D;
        double shadowPenalty = chunk.secondaryIndexCandidate() ? 0.25D : 0.0D;
        return overlap + protectedBonus - shadowPenalty;
    }

    private static Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_./-]+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }
}
