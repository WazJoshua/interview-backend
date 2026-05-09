package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import lombok.Builder;

import java.util.List;

/**
 * Result of structure-aware chunking containing candidate list and summary metrics.
 *
 * <p>This is the output of StructureAwareChunkingService, used for:
 * <ul>
 *   <li>Generating final DocumentChunk entities for persistence</li>
 *   <li>Producing shadow reports for offline evaluation</li>
 *   <li>Tracking block retention/drop statistics</li>
 * </ul>
 */
@Builder(toBuilder = true)
public record StructureAwareChunkingResult(
        List<ChunkCandidate> candidates,
        int retainedBlockCount,
        int droppedBlockCount,
        int totalDisplayChars,
        int totalEmbeddingChars
) {

    public StructureAwareChunkingResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}