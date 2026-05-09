package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for structure-aware chunking of NormalizedDocument.
 */
@Service
public class StructureAwareChunkingService {

    private static final Set<NormalizedBlockType> EXCLUDED_TYPES = Set.of(
            NormalizedBlockType.HEADER,
            NormalizedBlockType.FOOTER
    );
    private static final Set<NormalizedBlockType> HEADING_TYPES = Set.of(
            NormalizedBlockType.TITLE,
            NormalizedBlockType.HEADING
    );

    private final ChunkingProperties properties;
    private final ChunkCandidateFactory chunkCandidateFactory;

    public StructureAwareChunkingService(
            DocumentPreprocessingProperties preprocessingProperties,
            ChunkCandidateFactory chunkCandidateFactory
    ) {
        this.properties = preprocessingProperties.getChunking();
        this.chunkCandidateFactory = chunkCandidateFactory;
    }

    public StructureAwareChunkingResult chunk(NormalizedDocument document) {
        List<ChunkCandidate> candidates = new ArrayList<>();
        List<NormalizedBlock> indexableBlocks = filterIndexableBlocks(document.blocks());

        int retainedBlockCount = indexableBlocks.size();
        int droppedBlockCount = document.blocks().size() - retainedBlockCount;
        int chunkIndex = 0;
        int i = 0;
        while (i < indexableBlocks.size()) {
            NormalizedBlock current = indexableBlocks.get(i);
            if (isTableBlock(current)) {
                List<ChunkCandidate> tableChunks = processTableBlock(current, document, chunkIndex);
                candidates.addAll(tableChunks);
                chunkIndex += tableChunks.size();
                i++;
                continue;
            }
            if (isCodeBlock(current)) {
                List<ChunkCandidate> codeChunks = processCodeBlock(current, document, chunkIndex);
                candidates.addAll(codeChunks);
                chunkIndex += codeChunks.size();
                i++;
                continue;
            }
            if (isListBlock(current)) {
                NormalizedBlock leadSentence = resolveListLeadSentence(indexableBlocks, i, candidates);
                List<NormalizedBlock> listGroup = collectListGroup(indexableBlocks, i);
                if (leadSentence != null) {
                    listGroup = new ArrayList<>(listGroup);
                    listGroup.add(0, leadSentence);
                }
                candidates.add(chunkCandidateFactory.createFromBlocks(chunkIndex++, listGroup, document));
                i += listGroup.size() - (leadSentence != null ? 1 : 0);
                continue;
            }

            List<NormalizedBlock> paragraphGroup = collectParagraphGroup(indexableBlocks, i);
            if (shouldSkipParagraphLeadSentence(paragraphGroup, indexableBlocks, i)) {
                i++;
                continue;
            }
            candidates.add(chunkCandidateFactory.createFromBlocks(chunkIndex++, paragraphGroup, document));
            i += paragraphGroup.size();
        }

        int totalDisplayChars = candidates.stream().mapToInt(candidate -> candidate.displayText().length()).sum();
        int totalEmbeddingChars = candidates.stream().mapToInt(candidate -> candidate.embeddingText().length()).sum();

        return StructureAwareChunkingResult.builder()
                .candidates(candidates)
                .retainedBlockCount(retainedBlockCount)
                .droppedBlockCount(droppedBlockCount)
                .totalDisplayChars(totalDisplayChars)
                .totalEmbeddingChars(totalEmbeddingChars)
                .build();
    }

    private NormalizedBlock resolveListLeadSentence(List<NormalizedBlock> blocks, int startIndex, List<ChunkCandidate> candidates) {
        if (startIndex <= 0) {
            return null;
        }
        NormalizedBlock current = blocks.get(startIndex);
        NormalizedBlock previous = blocks.get(startIndex - 1);
        String currentSectionKey = String.join("/", current.sectionPath());
        String previousSectionKey = String.join("/", previous.sectionPath());
        if (previous.type() == NormalizedBlockType.PARAGRAPH
                && previous.text() != null
                && previous.text().length() < 100
                && previousSectionKey.equals(currentSectionKey)
                && !isBlockInAnyCandidate(previous, candidates)) {
            return previous;
        }
        return null;
    }

    private boolean shouldSkipParagraphLeadSentence(
            List<NormalizedBlock> paragraphGroup,
            List<NormalizedBlock> indexableBlocks,
            int currentIndex
    ) {
        if (paragraphGroup.size() != 1) {
            return false;
        }
        NormalizedBlock singleBlock = paragraphGroup.get(0);
        if (singleBlock.type() != NormalizedBlockType.PARAGRAPH
                || singleBlock.text() == null
                || singleBlock.text().length() >= 100
                || currentIndex + 1 >= indexableBlocks.size()) {
            return false;
        }
        NormalizedBlock nextBlock = indexableBlocks.get(currentIndex + 1);
        return isListBlock(nextBlock) && nextBlock.sectionPath().equals(singleBlock.sectionPath());
    }

    private boolean isBlockInAnyCandidate(NormalizedBlock block, List<ChunkCandidate> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.blockOrders().contains(block.order()));
    }

    private List<NormalizedBlock> filterIndexableBlocks(List<NormalizedBlock> blocks) {
        return blocks.stream().filter(this::isIndexable).toList();
    }

    private boolean isIndexable(NormalizedBlock block) {
        if (EXCLUDED_TYPES.contains(block.type()) || HEADING_TYPES.contains(block.type())) {
            return false;
        }
        RetrievalDisposition disposition = resolveRetrievalDisposition(block);
        return disposition != RetrievalDisposition.DROP && disposition != RetrievalDisposition.SOFT_DEINDEX;
    }

    private RetrievalDisposition resolveRetrievalDisposition(NormalizedBlock block) {
        Object value = block.metadata().get("retrievalDisposition");
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return RetrievalDisposition.valueOf(stringValue.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return RetrievalDisposition.KEEP;
            }
        }
        return RetrievalDisposition.KEEP;
    }

    private boolean isTableBlock(NormalizedBlock block) {
        return block.type() == NormalizedBlockType.TABLE;
    }

    private boolean isCodeBlock(NormalizedBlock block) {
        return block.type() == NormalizedBlockType.CODE;
    }

    private boolean isListBlock(NormalizedBlock block) {
        return block.type() == NormalizedBlockType.LIST_ITEM;
    }

    private List<NormalizedBlock> collectListGroup(List<NormalizedBlock> blocks, int startIndex) {
        List<NormalizedBlock> group = new ArrayList<>();
        String currentSectionKey = String.join("/", blocks.get(startIndex).sectionPath());
        for (int i = startIndex; i < blocks.size(); i++) {
            NormalizedBlock block = blocks.get(i);
            String blockSectionKey = String.join("/", block.sectionPath());
            if (block.type() == NormalizedBlockType.LIST_ITEM && blockSectionKey.equals(currentSectionKey)) {
                group.add(block);
                continue;
            }
            break;
        }
        return group.isEmpty() ? List.of(blocks.get(startIndex)) : group;
    }

    private List<NormalizedBlock> collectParagraphGroup(List<NormalizedBlock> blocks, int startIndex) {
        List<NormalizedBlock> group = new ArrayList<>();
        NormalizedBlock first = blocks.get(startIndex);
        List<String> currentSectionPath = first.sectionPath();
        int currentCharCount = 0;
        for (int i = startIndex; i < blocks.size(); i++) {
            NormalizedBlock block = blocks.get(i);
            if (!block.sectionPath().equals(currentSectionPath)) {
                break;
            }
            if (isTableBlock(block) || isCodeBlock(block) || isListBlock(block)) {
                break;
            }
            int blockLength = block.text() == null ? 0 : block.text().length();
            if (currentCharCount + blockLength > properties.getParagraphHardChars()) {
                break;
            }
            group.add(block);
            currentCharCount += blockLength;
            if (currentCharCount >= properties.getParagraphSoftChars()) {
                break;
            }
        }
        return group.isEmpty() ? List.of(first) : group;
    }

    private List<ChunkCandidate> processTableBlock(NormalizedBlock block, NormalizedDocument document, int startIndex) {
        List<ChunkCandidate> candidates = new ArrayList<>();
        String text = block.text() == null ? "" : block.text();
        int rows = resolveRowCount(block);
        if (rows <= properties.getTableSplitTriggerRows() && text.length() <= properties.getTableHardChars()) {
            candidates.add(chunkCandidateFactory.createFromSingleBlock(startIndex, block, document));
            return candidates;
        }

        String[] lines = text.split("\n");
        String header = lines.length > 0 ? lines[0] : "";
        List<String> dataLines = lines.length > 1
                ? Arrays.asList(Arrays.copyOfRange(lines, 1, lines.length))
                : List.of();
        int chunkIndex = startIndex;
        int partIndex = 0;
        int totalParts = (int) Math.ceil((double) dataLines.size() / properties.getTableMaxRowsPerChunk());
        for (int startRow = 0; startRow < dataLines.size(); startRow += properties.getTableMaxRowsPerChunk()) {
            int endRow = Math.min(startRow + properties.getTableMaxRowsPerChunk(), dataLines.size());
            List<String> chunkLines = new ArrayList<>();
            if (partIndex == 0 || !header.isEmpty()) {
                chunkLines.add(header);
            }
            chunkLines.addAll(dataLines.subList(startRow, endRow));
            candidates.add(chunkCandidateFactory.createFromSplitBlock(
                    chunkIndex++,
                    block,
                    document,
                    String.join("\n", chunkLines),
                    partIndex,
                    totalParts,
                    null,
                    null
            ));
            partIndex++;
        }
        return candidates;
    }

    private List<ChunkCandidate> processCodeBlock(NormalizedBlock block, NormalizedDocument document, int startIndex) {
        List<ChunkCandidate> candidates = new ArrayList<>();
        String text = block.text() == null ? "" : block.text();
        String[] lines = text.split("\n");
        if (lines.length <= properties.getCodeSplitTriggerLines()) {
            candidates.add(chunkCandidateFactory.createFromSingleBlock(startIndex, block, document));
            return candidates;
        }

        int chunkIndex = startIndex;
        int partIndex = 0;
        int totalParts = (int) Math.ceil((double) lines.length / properties.getCodeMaxLinesPerChunk());
        for (int startLine = 0; startLine < lines.length; startLine += properties.getCodeMaxLinesPerChunk()) {
            int endLine = Math.min(startLine + properties.getCodeMaxLinesPerChunk(), lines.length);
            candidates.add(chunkCandidateFactory.createFromSplitBlock(
                    chunkIndex++,
                    block,
                    document,
                    String.join("\n", Arrays.copyOfRange(lines, startLine, endLine)),
                    null,
                    null,
                    partIndex,
                    totalParts
            ));
            partIndex++;
        }
        return candidates;
    }

    private int resolveRowCount(NormalizedBlock block) {
        Object rows = block.metadata().get("rows");
        if (rows instanceof Number number) {
            return number.intValue();
        }
        String text = block.text() == null ? "" : block.text();
        return text.split("\n").length;
    }
}
