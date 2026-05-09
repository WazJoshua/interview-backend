package com.josh.interviewj.ragqa.service;

import com.josh.interviewj.ragqa.config.ContextAssemblyProperties;
import com.josh.interviewj.ragqa.model.ContextBlock;
import com.josh.interviewj.ragqa.model.ContextBlockAssemblyResult;
import com.josh.interviewj.ragqa.model.RankedChunkCandidate;
import com.josh.interviewj.ragqa.model.RetrievalProvenance;
import com.josh.interviewj.ragqa.repository.ChunkNeighborRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContextBlockAssembler {

    private static final int BLOCK_HEADER_OVERHEAD = 12;

    private final ChunkNeighborRepository chunkNeighborRepository;
    private final ContextAssemblyProperties assemblyProperties;
    private final ObjectMapper objectMapper;

    public ContextBlockAssemblyResult assemble(List<RankedChunkCandidate> seeds) {
        if (!assemblyProperties.isEnabled()) {
            return ContextBlockAssemblyResult.degraded("context_assembly_disabled");
        }
        if (seeds == null || seeds.isEmpty()) {
            return new ContextBlockAssemblyResult(List.of(), 0, 0, 0, false, "none");
        }

        Map<Long, List<ChunkNeighborRepository.DocumentChunkSliceProjection>> documentCache = new LinkedHashMap<>();
        List<BlockDraft> assembledBlocks = new ArrayList<>();
        int sectionFallbackCount = 0;
        boolean degraded = false;
        String degradedReason = "none";

        for (List<RankedChunkCandidate> group : groupSeedsBySection(seeds)) {
            RankedChunkCandidate seed = group.getFirst();
            MetadataView metadataView = parseMetadata(seed.metadata());
            if (!metadataView.sectionPathValid()) {
                BlockDraft fallbackBlock = assembleFallbackBlock(seed, metadataView);
                if (fallbackBlock.assemblyStrategy() == ContextBlock.AssemblyStrategy.ADJACENCY_FALLBACK) {
                    sectionFallbackCount++;
                }
                assembledBlocks.add(fallbackBlock);
                continue;
            }

            try {
                List<ChunkNeighborRepository.DocumentChunkSliceProjection> orderedChunks = documentCache.computeIfAbsent(
                        seed.documentId(),
                        chunkNeighborRepository::findDocumentChunks
                );
                BlockDraft sectionBlock = assembleSectionPriorityBlock(group, metadataView, orderedChunks);
                if (sectionBlock == null) {
                    sectionFallbackCount++;
                    degraded = true;
                    degradedReason = "section_extraction_failed";
                    assembledBlocks.add(assembleFallbackBlock(seed, metadataView));
                } else {
                    assembledBlocks.add(sectionBlock);
                }
            } catch (RuntimeException exception) {
                sectionFallbackCount++;
                degraded = true;
                degradedReason = "section_extraction_failed";
                assembledBlocks.add(assembleFallbackBlock(seed, metadataView));
            }
        }

        MergeStats mergeStats = mergeOverlappingBlocks(assembledBlocks);
        return new ContextBlockAssemblyResult(
                mergeStats.blocks().stream().map(this::toContextBlock).toList(),
                mergeStats.mergedBlockCount(),
                mergeStats.overlapFilteredCount(),
                sectionFallbackCount,
                degraded,
                degradedReason
        );
    }

    private List<List<RankedChunkCandidate>> groupSeedsBySection(List<RankedChunkCandidate> seeds) {
        Map<GroupKey, List<RankedChunkCandidate>> groups = new LinkedHashMap<>();
        for (RankedChunkCandidate seed : seeds) {
            MetadataView metadata = parseMetadata(seed.metadata());
            GroupKey key = metadata.sectionPathValid()
                    ? new GroupKey(seed.documentId(), metadata.sectionPath())
                    : new GroupKey(seed.documentId(), List.of("__seed__", String.valueOf(seed.chunkIndex())));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(seed);
        }
        return new ArrayList<>(groups.values());
    }

    private BlockDraft assembleSectionPriorityBlock(
            List<RankedChunkCandidate> seeds,
            MetadataView seedMetadata,
            List<ChunkNeighborRepository.DocumentChunkSliceProjection> orderedChunks
    ) {
        List<ChunkSlice> matchingSlices = orderedChunks.stream()
                .map(this::toChunkSlice)
                .filter(slice -> slice.metadata().sectionPathValid())
                .filter(slice -> slice.metadata().sectionPath().equals(seedMetadata.sectionPath()))
                .toList();
        if (matchingSlices.isEmpty()) {
            return null;
        }

        Set<Integer> seedIndexes = seeds.stream()
                .map(RankedChunkCandidate::chunkIndex)
                .collect(Collectors.toCollection(TreeSet::new));
        List<ChunkSlice> contiguousSection = new ArrayList<>();
        List<ChunkSlice> selectedSlices = new ArrayList<>();
        for (ChunkSlice slice : matchingSlices) {
            if (contiguousSection.isEmpty()) {
                contiguousSection.add(slice);
                continue;
            }
            ChunkSlice previous = contiguousSection.getLast();
            if (slice.chunkIndex() == previous.chunkIndex() + 1) {
                contiguousSection.add(slice);
                continue;
            }
            addSeededSegment(selectedSlices, contiguousSection, seedIndexes);
            contiguousSection = new ArrayList<>();
            contiguousSection.add(slice);
        }
        addSeededSegment(selectedSlices, contiguousSection, seedIndexes);
        if (selectedSlices.isEmpty()) {
            return null;
        }
        return buildBlock(
                seeds,
                selectedSlices,
                ContextBlock.AssemblyStrategy.SECTION_PRIORITY,
                seedMetadata.sectionPath()
        );
    }

    private boolean containsAnySeed(List<ChunkSlice> slices, Set<Integer> seedIndexes) {
        return slices.stream().anyMatch(slice -> seedIndexes.contains(slice.chunkIndex()));
    }

    private void addSeededSegment(List<ChunkSlice> selectedSlices, List<ChunkSlice> candidateSegment, Set<Integer> seedIndexes) {
        if (containsAnySeed(candidateSegment, seedIndexes)) {
            selectedSlices.addAll(candidateSegment);
        }
    }

    private BlockDraft assembleFallbackBlock(RankedChunkCandidate seed, MetadataView metadataView) {
        List<ChunkNeighborRepository.NeighborChunkProjection> neighbors = chunkNeighborRepository.findNeighborChunks(
                seed.documentId(),
                Math.max(0, seed.chunkIndex() - assemblyProperties.getAdjacencyWindowSize()),
                seed.chunkIndex() + assemblyProperties.getAdjacencyWindowSize()
        );
        if (!neighbors.isEmpty()) {
            return buildBlock(
                    List.of(seed),
                    neighbors.stream().map(this::toChunkSlice).toList(),
                    ContextBlock.AssemblyStrategy.ADJACENCY_FALLBACK,
                    List.of()
            );
        }
        return buildBlock(
                List.of(seed),
                List.of(new ChunkSlice(seed.chunkIndex(), seed.content(), seed.metadata(), TokenEstimateUtils.estimate(seed.content()), metadataView)),
                ContextBlock.AssemblyStrategy.SINGLE_CHUNK,
                metadataView.sectionPath()
        );
    }

    private BlockDraft buildBlock(
            List<RankedChunkCandidate> seeds,
            List<ChunkSlice> rawSlices,
            ContextBlock.AssemblyStrategy strategy,
            List<String> sectionPath
    ) {
        List<ChunkSlice> slices = truncateSlices(rawSlices);
        Set<Integer> seedIndexes = seeds.stream()
                .map(RankedChunkCandidate::chunkIndex)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<RetrievalProvenance> provenances = seeds.stream()
                .flatMap(seed -> seed.provenances().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new BlockDraft(
                seeds.getFirst().documentId(),
                seeds.getFirst().documentExternalId(),
                seeds.getFirst().documentName(),
                sectionPath,
                new ArrayList<>(seedIndexes),
                slices,
                seeds.stream().mapToDouble(RankedChunkCandidate::stage1RelevanceScore).max().orElse(0D),
                provenances,
                strategy
        );
    }

    private List<ChunkSlice> truncateSlices(List<ChunkSlice> rawSlices) {
        if (rawSlices.isEmpty()) {
            return List.of();
        }
        List<ChunkSlice> selected = new ArrayList<>();
        int estimatedTokens = BLOCK_HEADER_OVERHEAD;
        for (ChunkSlice slice : rawSlices) {
            int candidateTokens = estimatedTokens + slice.estimatedTokens();
            if (!selected.isEmpty() && candidateTokens > assemblyProperties.getMaxBlockTokens()) {
                break;
            }
            selected.add(slice);
            estimatedTokens = candidateTokens;
        }
        return selected.isEmpty() ? List.of(rawSlices.getFirst()) : selected;
    }

    private ContextBlock toContextBlock(BlockDraft blockDraft) {
        MetadataView baseMetadata = blockDraft.slices().isEmpty() ? MetadataView.empty() : blockDraft.slices().getFirst().metadata();
        List<Integer> includedIndexes = blockDraft.slices().stream()
                .map(ChunkSlice::chunkIndex)
                .collect(Collectors.toCollection(ArrayList::new));
        String mergedText = blockDraft.slices().stream()
                .map(ChunkSlice::content)
                .collect(Collectors.joining("\n\n"));
        return new ContextBlock(
                blockDraft.documentId(),
                blockDraft.documentExternalId(),
                blockDraft.documentName(),
                blockDraft.sectionPath(),
                blockDraft.seedChunkIndexes(),
                includedIndexes,
                mergedText,
                estimateBlockTokens(blockDraft.slices()),
                blockDraft.stage1BestScore(),
                buildMetadataSummary(blockDraft.slices(), baseMetadata),
                blockDraft.provenances(),
                blockDraft.assemblyStrategy()
        );
    }

    private MergeStats mergeOverlappingBlocks(List<BlockDraft> blocks) {
        if (blocks.isEmpty()) {
            return new MergeStats(List.of(), 0, 0);
        }
        List<BlockDraft> sortedBlocks = new ArrayList<>(blocks);
        sortedBlocks.sort(Comparator.comparing(BlockDraft::documentId)
                .thenComparing((BlockDraft block) -> block.slices().isEmpty() ? Integer.MAX_VALUE : block.slices().getFirst().chunkIndex()));

        List<BlockDraft> mergedBlocks = new ArrayList<>();
        int mergedBlockCount = 0;
        int overlapFilteredCount = 0;
        for (BlockDraft block : sortedBlocks) {
            if (mergedBlocks.isEmpty()) {
                mergedBlocks.add(block);
                continue;
            }
            BlockDraft previous = mergedBlocks.getLast();
            if (shouldMerge(previous, block)) {
                mergedBlocks.set(mergedBlocks.size() - 1, merge(previous, block));
                mergedBlockCount++;
                overlapFilteredCount++;
            } else {
                mergedBlocks.add(block);
            }
        }
        return new MergeStats(mergedBlocks, mergedBlockCount, overlapFilteredCount);
    }

    private boolean shouldMerge(BlockDraft left, BlockDraft right) {
        if (!Objects.equals(left.documentId(), right.documentId())) {
            return false;
        }
        if (!Objects.equals(left.sectionPath(), right.sectionPath())) {
            return false;
        }
        Set<Integer> leftIndexes = left.slices().stream()
                .map(ChunkSlice::chunkIndex)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> rightIndexes = right.slices().stream()
                .map(ChunkSlice::chunkIndex)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (leftIndexes.isEmpty() || rightIndexes.isEmpty()) {
            return false;
        }
        long intersection = leftIndexes.stream().filter(rightIndexes::contains).count();
        if (intersection == 0L) {
            return false;
        }
        double ratio = intersection / (double) Math.min(leftIndexes.size(), rightIndexes.size());
        return ratio >= assemblyProperties.getOverlapMergeThreshold()
                && estimateMergedTokens(left, right) <= assemblyProperties.getMaxBlockTokens();
    }

    private int estimateMergedTokens(BlockDraft left, BlockDraft right) {
        Map<Integer, ChunkSlice> uniqueSlices = new TreeMap<>();
        left.slices().forEach(slice -> uniqueSlices.putIfAbsent(slice.chunkIndex(), slice));
        right.slices().forEach(slice -> uniqueSlices.putIfAbsent(slice.chunkIndex(), slice));
        return BLOCK_HEADER_OVERHEAD + uniqueSlices.values().stream().mapToInt(ChunkSlice::estimatedTokens).sum();
    }

    private BlockDraft merge(BlockDraft left, BlockDraft right) {
        Set<Integer> seedIndexes = new TreeSet<>(left.seedChunkIndexes());
        seedIndexes.addAll(right.seedChunkIndexes());
        Set<RetrievalProvenance> provenances = new LinkedHashSet<>(left.provenances());
        provenances.addAll(right.provenances());
        Map<Integer, ChunkSlice> uniqueSlices = new TreeMap<>();
        left.slices().forEach(slice -> uniqueSlices.putIfAbsent(slice.chunkIndex(), slice));
        right.slices().forEach(slice -> uniqueSlices.putIfAbsent(slice.chunkIndex(), slice));
        return new BlockDraft(
                left.documentId(),
                left.documentExternalId(),
                left.documentName(),
                left.sectionPath().isEmpty() ? right.sectionPath() : left.sectionPath(),
                new ArrayList<>(seedIndexes),
                new ArrayList<>(uniqueSlices.values()),
                Math.max(left.stage1BestScore(), right.stage1BestScore()),
                provenances,
                left.assemblyStrategy() == ContextBlock.AssemblyStrategy.SECTION_PRIORITY
                        || right.assemblyStrategy() == ContextBlock.AssemblyStrategy.SECTION_PRIORITY
                        ? ContextBlock.AssemblyStrategy.SECTION_PRIORITY
                        : ContextBlock.AssemblyStrategy.ADJACENCY_FALLBACK
        );
    }

    private Map<String, Object> buildMetadataSummary(List<ChunkSlice> slices, MetadataView baseMetadata) {
        Set<String> blockTypes = slices.stream()
                .flatMap(slice -> slice.metadata().blockTypes().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Map.of(
                "sourceType", baseMetadata.sourceType(),
                "pageNumberRange", baseMetadata.pageNumberRange(),
                "blockTypes", new ArrayList<>(blockTypes),
                "sectionPathConfidence", baseMetadata.sectionPathConfidence()
        );
    }

    private int estimateBlockTokens(List<ChunkSlice> slices) {
        return Math.min(
                assemblyProperties.getMaxBlockTokens(),
                BLOCK_HEADER_OVERHEAD + slices.stream().mapToInt(ChunkSlice::estimatedTokens).sum()
        );
    }

    private ChunkSlice toChunkSlice(ChunkNeighborRepository.DocumentChunkSliceProjection projection) {
        MetadataView metadata = parseMetadata(projection.getMetadata());
        int estimatedTokens = projection.getTokenCount() == null || projection.getTokenCount() <= 0
                ? TokenEstimateUtils.estimate(projection.getContent())
                : projection.getTokenCount();
        return new ChunkSlice(projection.getChunkIndex(), projection.getContent(), projection.getMetadata(), estimatedTokens, metadata);
    }

    private ChunkSlice toChunkSlice(ChunkNeighborRepository.NeighborChunkProjection projection) {
        MetadataView metadata = parseMetadata(projection.getMetadata());
        int estimatedTokens = projection.getTokenCount() == null || projection.getTokenCount() <= 0
                ? TokenEstimateUtils.estimate(projection.getContent())
                : projection.getTokenCount();
        return new ChunkSlice(projection.getChunkIndex(), projection.getContent(), projection.getMetadata(), estimatedTokens, metadata);
    }

    private MetadataView parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return MetadataView.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(metadataJson);
            List<String> sectionPath = new ArrayList<>();
            JsonNode sectionPathNode = root.path("sectionPath");
            if (sectionPathNode.isArray()) {
                for (JsonNode node : sectionPathNode) {
                    if (node.isTextual() && !node.asText().isBlank()) {
                        sectionPath.add(node.asText());
                    }
                }
            }
            List<String> blockTypes = new ArrayList<>();
            JsonNode blockTypesNode = root.path("blockTypes");
            if (blockTypesNode.isArray()) {
                for (JsonNode node : blockTypesNode) {
                    if (node.isTextual() && !node.asText().isBlank()) {
                        blockTypes.add(node.asText());
                    }
                }
            }
            Map<String, Object> pageNumberRange = root.path("pageNumberRange").isObject()
                    ? objectMapper.convertValue(root.path("pageNumberRange"), Map.class)
                    : Map.of();
            String sourceType = root.path("sourceType").asText("");
            double confidence = root.path("sectionPathConfidence").asDouble(0D);
            boolean normalized = root.path("sectionPathNormalized").asBoolean(false);
            boolean valid = !sectionPath.isEmpty()
                    && confidence >= assemblyProperties.getSectionPathConfidenceThreshold()
                    && (!normalized || "MARKDOWN".equalsIgnoreCase(sourceType) || "DOCX".equalsIgnoreCase(sourceType));
            return new MetadataView(true, sectionPath, blockTypes, pageNumberRange, sourceType, confidence, normalized, valid);
        } catch (Exception exception) {
            return MetadataView.empty();
        }
    }

    private record GroupKey(Long documentId, List<String> sectionPath) {
    }

    private record ChunkSlice(
            Integer chunkIndex,
            String content,
            String metadataJson,
            int estimatedTokens,
            MetadataView metadata
    ) {
    }

    private record BlockDraft(
            Long documentId,
            UUID documentExternalId,
            String documentName,
            List<String> sectionPath,
            List<Integer> seedChunkIndexes,
            List<ChunkSlice> slices,
            double stage1BestScore,
            Set<RetrievalProvenance> provenances,
            ContextBlock.AssemblyStrategy assemblyStrategy
    ) {
        private BlockDraft {
            sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
            seedChunkIndexes = seedChunkIndexes == null ? List.of() : List.copyOf(seedChunkIndexes);
            slices = slices == null ? List.of() : List.copyOf(slices);
            provenances = provenances == null ? Set.of() : Set.copyOf(provenances);
        }
    }

    private record MetadataView(
            boolean metadataPresent,
            List<String> sectionPath,
            List<String> blockTypes,
            Map<String, Object> pageNumberRange,
            String sourceType,
            double sectionPathConfidence,
            boolean sectionPathNormalized,
            boolean sectionPathValid
    ) {
        private static MetadataView empty() {
            return new MetadataView(false, List.of(), List.of(), Map.of(), "", 0D, false, false);
        }
    }

    private record MergeStats(
            List<BlockDraft> blocks,
            int mergedBlockCount,
            int overlapFilteredCount
    ) {
    }
}
