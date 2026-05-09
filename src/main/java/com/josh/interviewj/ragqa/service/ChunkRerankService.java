package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.llm.gateway.AiOperationGateway;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationInput;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.ragqa.model.DatabaseRerankConfig;
import com.josh.interviewj.ragqa.model.NormalizedQuery;
import com.josh.interviewj.ragqa.model.PreRerankCandidate;
import com.josh.interviewj.ragqa.model.QueryProfile;
import com.josh.interviewj.ragqa.model.RankedChunkCandidate;
import com.josh.interviewj.ragqa.model.RerankResponse;
import com.josh.interviewj.ragqa.model.RewriteResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChunkRerankService {

    private static final String PURPOSE_KB_QUERY_RERANK = "kb_query_rerank";

    private final AiOperationGateway aiOperationGateway;
    private final DatabaseRerankConfigResolver databaseRerankConfigResolver;

    public ChunkRerankResult rerank(
            List<PreRerankCandidate> candidates,
            NormalizedQuery normalizedQuery,
            RewriteResult rewriteResult
    ) {
        String businessOperationId = PURPOSE_KB_QUERY_RERANK + "-standalone-" + UUID.randomUUID();
        return rerank(
                new BusinessOperationContext(
                        businessOperationId,
                        0L,
                        "KNOWLEDGE_BASE_QUERY",
                        businessOperationId,
                        PURPOSE_KB_QUERY_RERANK,
                        List.of("KB_QUERY_CREDITS"),
                        Collections.emptyMap()
                ),
                businessOperationId,
                candidates,
                normalizedQuery,
                rewriteResult
        );
    }

    public ChunkRerankResult rerank(
            BusinessOperationContext operationContext,
            String invocationPrefix,
            List<PreRerankCandidate> candidates,
            NormalizedQuery normalizedQuery,
            RewriteResult rewriteResult
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return new ChunkRerankResult(List.of(), false, "none");
        }
        DatabaseRerankConfig config = requireConfig();

        List<String> documents = candidates.stream()
                .map(PreRerankCandidate::content)
                .toList();
        List<RerankInvocation> invocations = new java.util.ArrayList<>();
        try {
            RerankInvocation primaryInvocation = executeRerank(operationContext, invocationPrefix + ":primary", normalizedQuery.normalizedText(), documents);
            RerankResponse primaryResponse = primaryInvocation.response();
            invocations.add(primaryInvocation);
            Map<Integer, Double> primaryScores = toScoreMap(primaryResponse);
            Map<Integer, Double> secondaryScores;
            if (shouldUseDualQuery(config, normalizedQuery, rewriteResult)) {
                RerankInvocation secondaryInvocation = executeRerank(operationContext, invocationPrefix + ":secondary", rewriteResult.rewrittenText(), documents);
                RerankResponse secondaryResponse = secondaryInvocation.response();
                invocations.add(secondaryInvocation);
                secondaryScores = toScoreMap(secondaryResponse);
            } else {
                secondaryScores = Map.of();
            }
            final Map<Integer, Double> finalPrimaryScores = primaryScores;
            final Map<Integer, Double> finalSecondaryScores = secondaryScores;

            List<RankedChunkCandidate> retained = IntStream.range(0, candidates.size())
                    .mapToObj(index -> toRankedCandidate(candidates.get(index), finalPrimaryScores, finalSecondaryScores, index))
                    .sorted(Comparator.comparingDouble(RankedChunkCandidate::stage1RelevanceScore).reversed()
                            .thenComparing(Comparator.comparingDouble(RankedChunkCandidate::rrfScore).reversed())
                            .thenComparing(RankedChunkCandidate::documentId)
                            .thenComparing(RankedChunkCandidate::chunkIndex))
                    .toList();

            List<RankedChunkCandidate> thresholded = retained.stream()
                    .filter(candidate -> candidate.stage1RelevanceScore() >= config.stage1RelevanceThreshold())
                    .limit(config.stage1TopN())
                    .toList();
            if (!thresholded.isEmpty()) {
                return new ChunkRerankResult(thresholded, false, "none", invocations);
            }
            return new ChunkRerankResult(
                    retained.stream().limit(config.stage1TopN()).toList(),
                    false,
                    "none",
                    invocations
            );
        } catch (RerankException exception) {
            return new ChunkRerankResult(degradeByPreRerankOrder(candidates), true, exception.getMessage(), invocations);
        }
    }

    private boolean shouldUseDualQuery(
            DatabaseRerankConfig config,
            NormalizedQuery normalizedQuery,
            RewriteResult rewriteResult
    ) {
        return config.dualQueryEnabled()
                && normalizedQuery != null
                && rewriteResult != null
                && rewriteResult.succeeded()
                && rewriteResult.rewrittenText() != null
                && !rewriteResult.rewrittenText().isBlank()
                && normalizedQuery.profile() != null
                && isSemanticDriftProfile(normalizedQuery.profile());
    }

    private boolean isSemanticDriftProfile(QueryProfile profile) {
        return profile.likelyConversational()
                || profile.likelyTerminologyDrift()
                || profile.longQuery();
    }

    private Map<Integer, Double> toScoreMap(RerankResponse response) {
        return response.results().stream()
                .collect(Collectors.toMap(
                        RerankResponse.ScoredDocument::index,
                        RerankResponse.ScoredDocument::relevanceScore,
                        Double::max
                ));
    }

    private RankedChunkCandidate toRankedCandidate(
            PreRerankCandidate candidate,
            Map<Integer, Double> primaryScores,
            Map<Integer, Double> secondaryScores,
            int candidateIndex
    ) {
        double primaryScore = primaryScores.getOrDefault(candidateIndex, 0D);
        double secondaryScore = secondaryScores.getOrDefault(candidateIndex, 0D);
        double finalScore = Math.max(primaryScore, secondaryScore);
        return new RankedChunkCandidate(
                candidate.documentId(),
                candidate.documentExternalId(),
                candidate.documentName(),
                candidate.chunkIndex(),
                candidate.content(),
                candidate.metadata(),
                candidate.provenances(),
                finalScore,
                candidate.bestDenseSimilarity(),
                candidate.rrfScore()
        );
    }

    private List<RankedChunkCandidate> degradeByPreRerankOrder(List<PreRerankCandidate> candidates) {
        DatabaseRerankConfig config = requireConfig();
        return candidates.stream()
                .limit(config.stage1TopN())
                .map(candidate -> new RankedChunkCandidate(
                        candidate.documentId(),
                        candidate.documentExternalId(),
                        candidate.documentName(),
                        candidate.chunkIndex(),
                        candidate.content(),
                        candidate.metadata(),
                        candidate.provenances(),
                        candidate.rrfScore(),
                        candidate.bestDenseSimilarity(),
                        candidate.rrfScore()
                ))
                .toList();
    }

    private DatabaseRerankConfig requireConfig() {
        return databaseRerankConfigResolver.resolve(PURPOSE_KB_QUERY_RERANK)
                .orElseThrow(() -> new IllegalStateException("Rerank route is not configured for purpose: " + PURPOSE_KB_QUERY_RERANK));
    }

    private RerankInvocation executeRerank(
            BusinessOperationContext operationContext,
            String invocationId,
            String query,
            List<String> documents
    ) {
        AiInvocationContext invocationContext = new AiInvocationContext(
                invocationId,
                PURPOSE_KB_QUERY_RERANK,
                com.josh.interviewj.usage.model.UsageFamily.RERANK,
                "KB_QUERY_CREDITS",
                true,
                Map.of()
        );
        AiInvocationResult invocationResult = aiOperationGateway.executeInvocation(
                operationContext,
                invocationContext,
                AiInvocationInput.rerank(query, documents)
        );
        return new RerankInvocation(
                invocationId,
                invocationResult.provider(),
                invocationResult.rerankResponse(),
                invocationContext,
                invocationResult
        );
    }

    public record ChunkRerankResult(
            List<RankedChunkCandidate> rankedCandidates,
            boolean degraded,
            String degradedReason,
            List<RerankInvocation> invocations
    ) {
        public ChunkRerankResult(
                List<RankedChunkCandidate> rankedCandidates,
                boolean degraded,
                String degradedReason
        ) {
            this(rankedCandidates, degraded, degradedReason, List.of());
        }

        public ChunkRerankResult {
            rankedCandidates = rankedCandidates == null ? List.of() : List.copyOf(rankedCandidates);
            degradedReason = degradedReason == null || degradedReason.isBlank() ? "none" : degradedReason;
            invocations = invocations == null ? List.of() : List.copyOf(invocations);
        }
    }

    public record RerankInvocation(
            String operationSuffix,
            String provider,
            RerankResponse response,
            AiInvocationContext invocationContext,
            AiInvocationResult invocationResult
    ) {
    }
}
