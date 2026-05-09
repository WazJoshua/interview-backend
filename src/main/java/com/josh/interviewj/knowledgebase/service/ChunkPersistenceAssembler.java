package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkCandidate;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkPersistencePayload;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInput;
import com.josh.interviewj.knowledgebase.preprocessing.sparse.ChunkSparseMaterialization;
import com.josh.interviewj.knowledgebase.preprocessing.sparse.ChunkSparseMaterializer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Assembles persistence metadata and sparse payloads from typed chunk candidates.
 */
@Service
public class ChunkPersistenceAssembler {

    private static final double SECONDARY_INDEX_CANDIDATE_RATIO_THRESHOLD = 0.6D;
    private static final String CHUNK_METADATA_VERSION = "KB_CHUNK_METADATA_V2";
    private static final String LEGACY_CHUNK_VERSION = "STRUCTURE_AWARE_V1";

    private final ObjectMapper objectMapper;
    private final ChunkSparseMaterializer chunkSparseMaterializer;

    public ChunkPersistenceAssembler(ObjectMapper objectMapper, ChunkSparseMaterializer chunkSparseMaterializer) {
        this.objectMapper = objectMapper;
        this.chunkSparseMaterializer = chunkSparseMaterializer;
    }

    public ChunkPersistencePayload assemble(ChunkCandidate candidate) {
        String metadataJson = buildMetadataJson(candidate);
        ChunkSparseMaterialization sparseMaterialization = chunkSparseMaterializer.materialize(
                candidate.displayText(),
                metadataJson
        );
        return ChunkPersistencePayload.builder()
                .metadataJson(sparseMaterialization.metadataJson())
                .sparseContentText(sparseMaterialization.sparseContentText())
                .sparseEntitiesText(sparseMaterialization.sparseEntitiesText())
                .sparseExactTerms(sparseMaterialization.sparseExactTerms())
                .build();
    }

    private String buildMetadataJson(ChunkCandidate candidate) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkVersion", LEGACY_CHUNK_VERSION);
        metadata.put("chunkMetadataVersion", CHUNK_METADATA_VERSION);
        putIfNotBlank(metadata, "preprocessingVersion", candidate.documentContext().preprocessingVersion());
        putIfNotBlank(metadata, "sourceType", candidate.documentContext().sourceType());
        putIfNotBlank(metadata, "documentTitle", candidate.documentContext().documentTitle());
        metadata.put("sectionPath", candidate.sectionPath());
        metadata.put("blockTypes", candidate.blockTypes());
        metadata.put("pageNumbers", candidate.pageNumbers());
        metadata.put("blockOrders", candidate.blockOrders());
        metadata.put("hasParentContext", candidate.hasParentContext());

        List<FixedSizeChunkingInput.RetainedSegment> retainedSegments = candidate.derivationContext().retainedSegments();
        if (!retainedSegments.isEmpty()) {
            putIfPresent(metadata, "pageNumberRange", buildPageNumberRange(retainedSegments));
            metadata.put("qualityFlags", retainedSegments.stream()
                    .flatMap(segment -> segment.qualityFlags().stream())
                    .distinct()
                    .sorted()
                    .toList());
            metadata.put("preprocessingWarnings", retainedSegments.stream()
                    .flatMap(segment -> segment.preprocessingWarnings().stream())
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList());
            metadata.put("retrievalDispositionSummary", buildRetrievalDispositionSummary(retainedSegments));
            metadata.put("retrievalDispositionReasonCodes", retainedSegments.stream()
                    .flatMap(segment -> segment.retrievalDispositionReasonCodes().stream())
                    .map(Enum::name)
                    .distinct()
                    .sorted()
                    .toList());
            metadata.put("softDeindexBlockRatio", calculateSoftDeindexBlockRatio(retainedSegments));
            metadata.put("hasProtectedAnchor", hasProtectedAnchor(retainedSegments));
            metadata.put("hasExplanatoryBodyEvidence", hasExplanatoryBodyEvidence(retainedSegments));
            metadata.put("secondaryIndexCandidate", isSecondaryIndexCandidate(retainedSegments));
        } else {
            metadata.put("qualityFlags", List.of());
            metadata.put("preprocessingWarnings", List.of());
        }

        putIfPresent(metadata, "tablePartIndex", candidate.derivationContext().tablePartIndex());
        putIfPresent(metadata, "tablePartCount", candidate.derivationContext().tablePartCount());
        putIfPresent(metadata, "codeSegmentIndex", candidate.derivationContext().codeSegmentIndex());
        putIfPresent(metadata, "codeSegmentCount", candidate.derivationContext().codeSegmentCount());
        putIfPresent(metadata, "startPosition", candidate.derivationContext().startPosition());
        putIfPresent(metadata, "endPosition", candidate.derivationContext().endPosition());

        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to assemble chunk metadata", ex);
        }
    }

    private Map<String, Integer> buildPageNumberRange(List<FixedSizeChunkingInput.RetainedSegment> segments) {
        List<Integer> pageNumbers = segments.stream()
                .map(FixedSizeChunkingInput.RetainedSegment::pageNumber)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (pageNumbers.isEmpty()) {
            return null;
        }
        return Map.of("start", pageNumbers.get(0), "end", pageNumbers.get(pageNumbers.size() - 1));
    }

    private Map<String, Integer> buildRetrievalDispositionSummary(List<FixedSizeChunkingInput.RetainedSegment> segments) {
        Map<String, Integer> summary = new TreeMap<>();
        Arrays.stream(RetrievalDisposition.values()).forEach(disposition -> summary.put(disposition.name(), 0));
        for (FixedSizeChunkingInput.RetainedSegment segment : segments) {
            summary.compute(segment.retrievalDisposition().name(), (key, count) -> count == null ? 1 : count + 1);
        }
        summary.entrySet().removeIf(entry -> entry.getValue() == 0);
        return summary;
    }

    private double calculateSoftDeindexBlockRatio(List<FixedSizeChunkingInput.RetainedSegment> segments) {
        if (segments.isEmpty()) {
            return 0.0D;
        }
        long softDeindexBlockCount = segments.stream()
                .filter(segment -> segment.retrievalDisposition() == RetrievalDisposition.SOFT_DEINDEX)
                .count();
        return softDeindexBlockCount / (double) segments.size();
    }

    private boolean hasProtectedAnchor(List<FixedSizeChunkingInput.RetainedSegment> segments) {
        return segments.stream().anyMatch(segment ->
                segment.retrievalDisposition() == RetrievalDisposition.PROTECT
                        || segment.qualityFlags().contains("HAS_PROTECTED_ANCHOR")
        );
    }

    private boolean hasExplanatoryBodyEvidence(List<FixedSizeChunkingInput.RetainedSegment> segments) {
        return segments.stream().anyMatch(segment -> segment.retrievalDisposition() == RetrievalDisposition.KEEP);
    }

    private boolean isSecondaryIndexCandidate(List<FixedSizeChunkingInput.RetainedSegment> segments) {
        double softDeindexBlockRatio = calculateSoftDeindexBlockRatio(segments);
        return softDeindexBlockRatio >= SECONDARY_INDEX_CANDIDATE_RATIO_THRESHOLD
                && !hasProtectedAnchor(segments)
                && !hasExplanatoryBodyEvidence(segments);
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
