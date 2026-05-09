package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.config.ContextSelectionProperties;
import com.josh.interviewj.ragqa.model.ContextAssemblyResult;
import com.josh.interviewj.ragqa.model.ContextBlock;
import com.josh.interviewj.ragqa.model.RetrievalMode;
import com.josh.interviewj.ragqa.model.RetrievalProvenance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CoverageAwareContextSelector {

    private static final double DOCUMENT_NOVELTY_BONUS = 0.15D;
    private static final double SECTION_NOVELTY_BONUS = 0.10D;
    private static final double TOKEN_PENALTY_WEIGHT = 0.8D;
    private static final double OVERLAP_PENALTY = 0.12D;

    private final ContextSelectionProperties selectionProperties;

    public ContextAssemblyResult select(List<ContextBlock> blocks, int availableContextBudget) {
        if (blocks == null || blocks.isEmpty()) {
            return ContextAssemblyResult.degraded("no_blocks_available");
        }
        if (availableContextBudget <= 0) {
            return ContextAssemblyResult.degraded("no_available_context_budget");
        }

        List<ContextBlock> selected = new ArrayList<>();
        Set<Long> selectedDocuments = new LinkedHashSet<>();
        Set<String> selectedSections = new LinkedHashSet<>();
        Map<Long, Integer> documentCounts = new LinkedHashMap<>();
        Map<String, Integer> sectionCounts = new LinkedHashMap<>();
        List<ContextBlock> remaining = new ArrayList<>(blocks);
        int remainingBudget = availableContextBudget;
        int overlapFilteredCount = 0;
        int duplicateFilteredCount = 0;

        while (!remaining.isEmpty() && selected.size() < selectionProperties.getMaxBlocks()) {
            final int budgetLeft = remainingBudget;
            ContextBlock bestCandidate = remaining.stream()
                    .filter(block -> block.estimatedTokens() <= budgetLeft)
                    .filter(block -> documentCounts.getOrDefault(block.documentId(), 0) < selectionProperties.getDocumentMonopolyCap())
                    .filter(block -> sectionCounts.getOrDefault(sectionKey(block), 0) < selectionProperties.getSectionMonopolyCap())
                    .max(Comparator.comparingDouble(block -> score(block, availableContextBudget, selectedDocuments, selectedSections, selected)))
                    .orElse(null);

            if (bestCandidate == null) {
                break;
            }

            selected.add(bestCandidate);
            remaining.remove(bestCandidate);
            remainingBudget -= bestCandidate.estimatedTokens();
            selectedDocuments.add(bestCandidate.documentId());
            selectedSections.add(sectionKey(bestCandidate));
            documentCounts.merge(bestCandidate.documentId(), 1, Integer::sum);
            sectionCounts.merge(sectionKey(bestCandidate), 1, Integer::sum);

            List<ContextBlock> overlapped = remaining.stream()
                    .filter(candidate -> overlaps(bestCandidate, candidate))
                    .toList();
            overlapFilteredCount += overlapped.size();
            remaining.removeAll(overlapped);

            List<ContextBlock> duplicates = remaining.stream()
                    .filter(candidate -> sameBlock(bestCandidate, candidate))
                    .toList();
            duplicateFilteredCount += duplicates.size();
            remaining.removeAll(duplicates);
        }

        return new ContextAssemblyResult(
                selected,
                selected.stream().mapToInt(ContextBlock::estimatedTokens).sum(),
                selectedDocuments.size(),
                selectedSections.size(),
                duplicateFilteredCount,
                overlapFilteredCount,
                selected.isEmpty() ? "budget_exhausted" : "none"
        );
    }

    private double score(
            ContextBlock block,
            int availableContextBudget,
            Set<Long> selectedDocuments,
            Set<String> selectedSections,
            List<ContextBlock> selected
    ) {
        double score = block.stage1BestScore();
        if (!selectedDocuments.contains(block.documentId())) {
            score += DOCUMENT_NOVELTY_BONUS;
        }
        if (!selectedSections.contains(sectionKey(block))) {
            score += SECTION_NOVELTY_BONUS;
        }
        if (isSparseOnlyRescue(block)) {
            score += selectionProperties.getSparseRescueBonus();
        }
        if (hasConsensus(block)) {
            score += selectionProperties.getConsensusBonus();
        }
        if (selected.stream().anyMatch(existing -> overlaps(existing, block))) {
            score -= OVERLAP_PENALTY;
        }
        score -= TOKEN_PENALTY_WEIGHT * (block.estimatedTokens() / (double) Math.max(1, availableContextBudget));
        return score;
    }

    private boolean sameBlock(ContextBlock left, ContextBlock right) {
        return left.documentId().equals(right.documentId())
                && left.sectionPath().equals(right.sectionPath())
                && left.includedChunkIndexes().equals(right.includedChunkIndexes());
    }

    private boolean overlaps(ContextBlock left, ContextBlock right) {
        if (!left.documentId().equals(right.documentId())) {
            return false;
        }
        return left.includedChunkIndexes().stream().anyMatch(right.includedChunkIndexes()::contains);
    }

    private boolean isSparseOnlyRescue(ContextBlock block) {
        boolean hasSparse = block.provenances().stream().anyMatch(provenance -> provenance.retrievalMode() == RetrievalMode.SPARSE);
        boolean hasDense = block.provenances().stream().anyMatch(provenance -> provenance.retrievalMode() == RetrievalMode.DENSE);
        return hasSparse && !hasDense;
    }

    private boolean hasConsensus(ContextBlock block) {
        return block.provenances().stream()
                        .map(RetrievalProvenance::retrievalMode)
                        .distinct()
                        .count() > 1;
    }

    private String sectionKey(ContextBlock block) {
        return block.sectionPath().isEmpty()
                ? block.documentId() + ":__none__"
                : String.join(" > ", block.sectionPath());
    }
}
