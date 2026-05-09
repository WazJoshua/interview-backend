package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInput;
import com.josh.interviewj.knowledgebase.service.KbChunkingService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Builds typed chunk candidates for structure-aware and fixed-size flows.
 */
@Component
public class ChunkCandidateFactory {

    private static final String CHUNK_VERSION = "STRUCTURE_AWARE_V1";
    private static final String FIXED_SIZE_SOURCE_TYPE = "RAW_TEXT";

    private final ParentContextTemplateBuilder templateBuilder;

    public ChunkCandidateFactory(ParentContextTemplateBuilder templateBuilder) {
        this.templateBuilder = templateBuilder;
    }

    public ChunkCandidate createFromBlocks(
            int chunkIndex,
            List<NormalizedBlock> blocks,
            NormalizedDocument document
    ) {
        if (blocks == null || blocks.isEmpty()) {
            return ChunkCandidate.builder().chunkIndex(chunkIndex).build();
        }

        NormalizedBlock first = blocks.get(0);
        List<Integer> blockOrders = blocks.stream().map(NormalizedBlock::order).toList();
        List<String> blockTypes = blocks.stream().map(block -> block.type().name()).distinct().toList();
        List<Integer> pageNumbers = blocks.stream()
                .map(NormalizedBlock::pageNumber)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        String displayText = blocks.stream()
                .map(NormalizedBlock::text)
                .filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");

        return buildStructureAwareCandidate(
                chunkIndex,
                blockOrders,
                blockTypes,
                first.sectionPath(),
                pageNumbers,
                displayText,
                buildDocumentContext(document),
                ChunkDerivationContext.builder().build()
        );
    }

    public ChunkCandidate createFromSingleBlock(
            int chunkIndex,
            NormalizedBlock block,
            NormalizedDocument document
    ) {
        return createFromSplitBlock(
                chunkIndex,
                block,
                document,
                block.text() == null ? "" : block.text(),
                null,
                null,
                null,
                null
        );
    }

    public ChunkCandidate createFromSplitBlock(
            int chunkIndex,
            NormalizedBlock block,
            NormalizedDocument document,
            String displayText,
            Integer tablePartIndex,
            Integer tablePartCount,
            Integer codeSegmentIndex,
            Integer codeSegmentCount
    ) {
        String codeLanguage = resolveCodeLanguage(block);
        return buildStructureAwareCandidate(
                chunkIndex,
                List.of(block.order()),
                List.of(block.type().name()),
                block.sectionPath(),
                block.pageNumber() == null ? List.of() : List.of(block.pageNumber()),
                displayText == null ? "" : displayText,
                buildDocumentContext(document),
                ChunkDerivationContext.builder()
                        .tablePartIndex(tablePartIndex)
                        .tablePartCount(tablePartCount)
                        .codeSegmentIndex(codeSegmentIndex)
                        .codeSegmentCount(codeSegmentCount)
                        .codeLanguage(codeLanguage)
                        .build()
        );
    }

    public ChunkCandidate createFixedSizeCandidate(
            KbChunkingService.ChunkPart chunkPart,
            ChunkDocumentContext documentContext,
            List<FixedSizeChunkingInput.RetainedSegment> retainedSegments
    ) {
        List<FixedSizeChunkingInput.RetainedSegment> safeSegments = retainedSegments == null ? List.of() : retainedSegments;
        List<Integer> blockOrders = safeSegments.stream()
                .map(FixedSizeChunkingInput.RetainedSegment::blockOrder)
                .distinct()
                .sorted()
                .toList();
        List<String> blockTypes = safeSegments.stream()
                .map(FixedSizeChunkingInput.RetainedSegment::blockType)
                .filter(Objects::nonNull)
                .map(Enum::name)
                .distinct()
                .toList();
        List<Integer> pageNumbers = safeSegments.stream()
                .map(FixedSizeChunkingInput.RetainedSegment::pageNumber)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        ChunkCandidate baseCandidate = ChunkCandidate.builder()
                .chunkIndex(chunkPart.chunkIndex())
                .blockOrders(blockOrders)
                .bodyText(chunkPart.content())
                .displayText(chunkPart.content())
                .embeddingText(chunkPart.content())
                .tokenCountEstimate(chunkPart.tokenCount())
                .hasParentContext(false)
                .documentContext(documentContext == null
                        ? ChunkDocumentContext.builder().sourceType(FIXED_SIZE_SOURCE_TYPE).build()
                        : documentContext)
                .semanticContext(ChunkSemanticContext.builder()
                        .blockTypes(blockTypes)
                        .pageNumbers(pageNumbers)
                        .sectionPath(List.of())
                        .build())
                .derivationContext(ChunkDerivationContext.builder()
                        .startPosition(chunkPart.startPosition())
                        .endPosition(chunkPart.endPosition())
                        .retainedSegments(safeSegments)
                        .build())
                .build();
        return baseCandidate;
    }

    public String chunkVersion() {
        return CHUNK_VERSION;
    }

    private ChunkCandidate buildStructureAwareCandidate(
            int chunkIndex,
            List<Integer> blockOrders,
            List<String> blockTypes,
            List<String> sectionPath,
            List<Integer> pageNumbers,
            String displayText,
            ChunkDocumentContext documentContext,
            ChunkDerivationContext derivationContext
    ) {
        ChunkCandidate baseCandidate = ChunkCandidate.builder()
                .chunkIndex(chunkIndex)
                .blockOrders(blockOrders)
                .bodyText(displayText)
                .displayText(displayText)
                .embeddingText(displayText)
                .tokenCountEstimate(0)
                .hasParentContext(false)
                .documentContext(documentContext)
                .semanticContext(ChunkSemanticContext.builder()
                        .blockTypes(blockTypes)
                        .sectionPath(sectionPath)
                        .pageNumbers(pageNumbers)
                        .build())
                .derivationContext(derivationContext)
                .build();
        boolean hasParentContext = templateBuilder.hasParentContext(baseCandidate);
        String embeddingText = templateBuilder.buildEmbeddingText(baseCandidate);
        return baseCandidate.toBuilder()
                .hasParentContext(hasParentContext)
                .embeddingText(embeddingText)
                .tokenCountEstimate(estimateTokens(embeddingText))
                .build();
    }

    private ChunkDocumentContext buildDocumentContext(NormalizedDocument document) {
        return ChunkDocumentContext.builder()
                .sourceType(document.sourceType() == null ? "" : document.sourceType().name())
                .documentTitle(document.title())
                .fileName(document.fileName())
                .preprocessingVersion(String.valueOf(document.metadata().getOrDefault("preprocessingVersion", "v1")))
                .build();
    }

    private String resolveCodeLanguage(NormalizedBlock block) {
        Object language = block.metadata().get("language");
        if (language instanceof String languageValue && !languageValue.isBlank()) {
            return languageValue;
        }
        return null;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / 4;
    }
}
