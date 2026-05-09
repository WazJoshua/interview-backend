package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.model.FusedChunk;
import com.josh.interviewj.ragqa.model.FusedRetrievalResult;
import com.josh.interviewj.ragqa.model.QueryVariant;
import com.josh.interviewj.ragqa.model.RetrievalProvenance;
import com.josh.interviewj.ragqa.model.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class RetrievalResultFusionService {

    private static final int RRF_CONSTANT = 60;
    private static final int CROSS_BRANCH_MISMATCH_THRESHOLD = 20;

    public FusedRetrievalResult fuse(List<RetrievedChunk> candidates, int finalTopK) {
        Map<ChunkKey, Aggregate> aggregates = new LinkedHashMap<>();
        int originalHitCount = 0;
        int rewriteHitCount = 0;

        for (RetrievedChunk candidate : candidates) {
            if (candidate.queryVariant() == QueryVariant.ORIGINAL) {
                originalHitCount++;
            } else if (candidate.queryVariant() == QueryVariant.REWRITE) {
                rewriteHitCount++;
            }

            ChunkKey key = new ChunkKey(candidate.documentId(), candidate.chunkIndex());
            Aggregate aggregate = aggregates.computeIfAbsent(key, ignored -> new Aggregate(candidate));
            aggregate.add(candidate);
        }

        int sparseCandidateCount = (int) candidates.stream()
                .filter(candidate -> candidate.retrievalMode() == com.josh.interviewj.ragqa.model.RetrievalMode.SPARSE)
                .count();

        List<Aggregate> rankedAggregates = aggregates.values().stream()
                .sorted(Comparator
                        .comparingDouble(Aggregate::rrfScore).reversed()
                        .thenComparing(Aggregate::documentId)
                        .thenComparing(Aggregate::chunkIndex))
                .toList();

        List<Aggregate> selectedAggregates = selectWithCoverage(rankedAggregates, finalTopK);
        List<FusedChunk> selected = selectedAggregates.stream().map(Aggregate::toChunk).toList();
        int overlapHitCount = (int) rankedAggregates.stream().filter(aggregate -> aggregate.provenanceCount() > 1).count();
        int sparseSelectedCount = (int) selectedAggregates.stream().filter(Aggregate::hasSparseProvenance).count();
        int sparseOnlyRescueCount = (int) selectedAggregates.stream()
                .filter(aggregate -> aggregate.hasSparseProvenance() && !aggregate.hasDenseProvenance())
                .count();
        int crossBranchMismatchCount = (int) selectedAggregates.stream()
                .filter(Aggregate::isCrossBranchMismatch)
                .count();

        return new FusedRetrievalResult(
                selected,
                candidates.size(),
                rankedAggregates.size(),
                originalHitCount,
                rewriteHitCount,
                overlapHitCount,
                sparseCandidateCount,
                sparseSelectedCount,
                sparseOnlyRescueCount,
                crossBranchMismatchCount
        );
    }

    private List<Aggregate> selectWithCoverage(List<Aggregate> ranked, int finalTopK) {
        List<Aggregate> selected = new ArrayList<>();
        Map<Long, Integer> perDocumentCount = new LinkedHashMap<>();
        Set<ChunkKey> selectedKeys = java.util.HashSet.newHashSet(ranked.size());

        for (int round = 1; round <= 2 && selected.size() < finalTopK; round++) {
            for (Aggregate aggregate : ranked) {
                ChunkKey key = new ChunkKey(aggregate.documentId(), aggregate.chunkIndex());
                if (selectedKeys.contains(key)) {
                    continue;
                }
                int currentCount = perDocumentCount.getOrDefault(aggregate.documentId(), 0);
                if (currentCount >= round) {
                    continue;
                }
                selected.add(aggregate);
                selectedKeys.add(key);
                perDocumentCount.put(aggregate.documentId(), currentCount + 1);
                if (selected.size() >= finalTopK) {
                    return selected;
                }
            }
        }

        for (Aggregate aggregate : ranked) {
            ChunkKey key = new ChunkKey(aggregate.documentId(), aggregate.chunkIndex());
            if (selectedKeys.add(key)) {
                selected.add(aggregate);
                if (selected.size() >= finalTopK) {
                    break;
                }
            }
        }
        return selected;
    }

    private record ChunkKey(Long documentId, Integer chunkIndex) {
    }

    private static final class Aggregate {
        private final UUID documentExternalId;
        private final Long documentId;
        private final String documentName;
        private final Integer chunkIndex;
        private final String content;
        private double bestDenseSimilarity;
        private double rrfScore;
        private final Set<RetrievalProvenance> provenances = new LinkedHashSet<>();
        private int bestDenseRank = Integer.MAX_VALUE;

        private Aggregate(RetrievedChunk chunk) {
            this.documentExternalId = chunk.documentExternalId();
            this.documentId = chunk.documentId();
            this.documentName = chunk.documentName();
            this.chunkIndex = chunk.chunkIndex();
            this.content = chunk.content();
            this.bestDenseSimilarity = chunk.retrievalMode() == com.josh.interviewj.ragqa.model.RetrievalMode.DENSE
                    ? chunk.similarity()
                    : 0D;
        }

        private void add(RetrievedChunk chunk) {
            this.rrfScore += 1D / (RRF_CONSTANT + chunk.branchRank());
            this.provenances.add(new RetrievalProvenance(chunk.queryVariant(), chunk.retrievalMode()));
            if (chunk.retrievalMode() == com.josh.interviewj.ragqa.model.RetrievalMode.DENSE) {
                this.bestDenseSimilarity = Math.max(bestDenseSimilarity, chunk.similarity());
                this.bestDenseRank = Math.min(bestDenseRank, chunk.branchRank());
            }
        }

        private FusedChunk toChunk() {
            return new FusedChunk(documentExternalId, documentId, documentName, chunkIndex, content, bestDenseSimilarity, rrfScore, provenances);
        }

        private double rrfScore() {
            return rrfScore;
        }

        private double bestSimilarity() {
            return bestDenseSimilarity;
        }

        private Long documentId() {
            return documentId;
        }

        private Integer chunkIndex() {
            return chunkIndex;
        }

        private int provenanceCount() {
            return provenances.size();
        }

        private boolean hasSparseProvenance() {
            return provenances.stream().anyMatch(provenance -> provenance.retrievalMode() == com.josh.interviewj.ragqa.model.RetrievalMode.SPARSE);
        }

        private boolean hasDenseProvenance() {
            return provenances.stream().anyMatch(provenance -> provenance.retrievalMode() == com.josh.interviewj.ragqa.model.RetrievalMode.DENSE);
        }

        private boolean isCrossBranchMismatch() {
            return hasSparseProvenance() && (!hasDenseProvenance() || bestDenseRank > CROSS_BRANCH_MISMATCH_THRESHOLD);
        }
    }
}
