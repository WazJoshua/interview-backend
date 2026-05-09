package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.model.PreRerankCandidate;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievalProvenance;
import com.josh.interviewj.ragqa.model.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PreRerankCandidateBuilder {

    private static final int RRF_CONSTANT = 60;

    public List<PreRerankCandidate> build(List<RetrievedChunk> rawCandidates, int hardCap) {
        if (rawCandidates == null || rawCandidates.isEmpty() || hardCap <= 0) {
            return List.of();
        }

        Map<ChunkKey, Aggregate> aggregates = new LinkedHashMap<>();
        for (RetrievedChunk candidate : rawCandidates) {
            ChunkKey key = new ChunkKey(candidate.documentId(), candidate.chunkIndex());
            aggregates.computeIfAbsent(key, ignored -> new Aggregate(candidate)).add(candidate);
        }

        return aggregates.values().stream()
                .sorted(Comparator
                        .comparingDouble(Aggregate::rrfScore).reversed()
                        .thenComparing(Aggregate::hasDenseAndSparseConsensus, Comparator.reverseOrder())
                        .thenComparing(Aggregate::sparseOnlyRescue, Comparator.reverseOrder())
                        .thenComparingInt(Aggregate::bestDenseRank)
                        .thenComparing(Aggregate::documentId)
                        .thenComparing(Aggregate::chunkIndex))
                .limit(hardCap)
                .map(Aggregate::toCandidate)
                .toList();
    }

    private record ChunkKey(Long documentId, Integer chunkIndex) {
    }

    private static final class Aggregate {
        private final Long documentId;
        private final UUID documentExternalId;
        private final String documentName;
        private final Integer chunkIndex;
        private final String content;
        private final Set<RetrievalProvenance> provenances = new LinkedHashSet<>();
        private String metadata;
        private double rrfScore;
        private double bestDenseSimilarity;
        private int bestDenseRank = Integer.MAX_VALUE;
        private boolean hasDenseProvenance;
        private boolean hasSparseProvenance;

        private Aggregate(RetrievedChunk seed) {
            this.documentId = seed.documentId();
            this.documentExternalId = seed.documentExternalId();
            this.documentName = seed.documentName();
            this.chunkIndex = seed.chunkIndex();
            this.content = seed.content();
        }

        private void add(RetrievedChunk chunk) {
            rrfScore += 1D / (RRF_CONSTANT + chunk.branchRank());
            provenances.add(new RetrievalProvenance(chunk.queryVariant(), chunk.retrievalMode()));
            if (metadata == null && chunk.metadata() != null && !chunk.metadata().isBlank()) {
                metadata = chunk.metadata();
            }
            if (chunk.retrievalMode() == RetrievalMode.DENSE) {
                hasDenseProvenance = true;
                bestDenseSimilarity = Math.max(bestDenseSimilarity, chunk.similarity());
                bestDenseRank = Math.min(bestDenseRank, chunk.branchRank());
                return;
            }
            if (chunk.retrievalMode() == RetrievalMode.SPARSE) {
                hasSparseProvenance = true;
            }
        }

        private PreRerankCandidate toCandidate() {
            return new PreRerankCandidate(
                    documentId,
                    documentExternalId,
                    documentName,
                    chunkIndex,
                    content,
                    rrfScore,
                    bestDenseSimilarity,
                    bestDenseRank,
                    sparseOnlyRescue(),
                    hasDenseProvenance,
                    hasSparseProvenance,
                    provenances,
                    metadata
            );
        }

        private double rrfScore() {
            return rrfScore;
        }

        private boolean hasDenseAndSparseConsensus() {
            return hasDenseProvenance && hasSparseProvenance;
        }

        private boolean sparseOnlyRescue() {
            return hasSparseProvenance && !hasDenseProvenance;
        }

        private int bestDenseRank() {
            return bestDenseRank;
        }

        private Long documentId() {
            return documentId;
        }

        private Integer chunkIndex() {
            return chunkIndex;
        }
    }
}
