package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarning;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarningCategory;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentCleaningStep;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.StepMetric;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes section paths and enriches block metadata with section depth and confidence.
 *
 * <p>This step is the single source of truth for section path semantics.
 * Parsers provide raw section paths; this step normalizes them based on source type.
 *
 * <p>Source-specific normalization:
 * <ul>
 *   <li>MARKDOWN: Full support - paths preserved as-is, confidence = 1.0</li>
 *   <li>DOCX: Strong support - paths preserved, confidence = 1.0 (with heading level) or 0.8 (without)</li>
 *   <li>PDF: Weak support - shallow paths truncated, confidence = 0.3-0.5 based on depth</li>
 * </ul>
 *
 * <p>Metadata keys added to each NormalizedBlock:
 * <ul>
 *   <li>sectionDepth: depth of the normalized section path (0 for top-level)</li>
 *   <li>sectionPathConfidence: confidence score based on source type</li>
 *   <li>sectionPathNormalized: true if path was modified from parser output</li>
 * </ul>
 *
 * <p>Heading level gaps generate warnings in NormalizedDocument.warnings[] with code HEADING_LEVEL_GAP.
 */
@Component
@Order(95)  // Run after deduplication, before document metadata normalization
public class EnrichSectionPathStep implements DocumentCleaningStep {

    private static final String SECTION_DEPTH_KEY = "sectionDepth";
    private static final String SECTION_PATH_CONFIDENCE_KEY = "sectionPathConfidence";
    private static final String SECTION_PATH_NORMALIZED_KEY = "sectionPathNormalized";
    private static final String HEADING_LEVEL_KEY = "headingLevel";

    // Maximum section path depth for PDF sources (avoid deep, unreliable paths)
    private static final int PDF_MAX_SECTION_DEPTH = 2;

    private final ChunkingProperties chunkingProperties;

    public EnrichSectionPathStep(DocumentPreprocessingProperties properties) {
        this.chunkingProperties = properties.getChunking();
    }

    @Override
    public String getName() {
        return "enrichSectionPath";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        DocumentSourceType sourceType = context.parsedDocument().sourceType();
        List<NormalizedBlock> enrichedBlocks = new ArrayList<>();
        List<DocumentWarning> newWarnings = new ArrayList<>();
        Integer previousHeadingLevel = null;
        String previousHeadingText = null;

        for (NormalizedBlock block : context.workingBlocks()) {
            // Normalize section path based on source type
            List<String> normalizedPath = normalizeSectionPath(block.sectionPath(), sourceType);
            boolean wasNormalized = !normalizedPath.equals(block.sectionPath());

            // Detect heading level gaps (for DOCX and Markdown)
            if (isHeadingBlock(block) && previousHeadingLevel != null) {
                Integer currentLevel = getHeadingLevel(block);
                if (currentLevel != null && currentLevel > previousHeadingLevel + 1) {
                    // Gap detected: e.g., H1 -> H3 (missing H2)
                    newWarnings.add(createHeadingGapWarning(
                            block.order(),
                            previousHeadingLevel,
                            currentLevel,
                            previousHeadingText,
                            block.text()
                    ));
                }
            }

            // Update tracking for heading blocks
            if (isHeadingBlock(block)) {
                previousHeadingLevel = getHeadingLevel(block);
                previousHeadingText = block.text();
            }

            // Calculate confidence based on source type and normalized path
            double confidence = calculateConfidence(sourceType, normalizedPath);

            // Enrich block metadata with normalized path
            NormalizedBlock enrichedBlock = enrichBlock(block, normalizedPath, confidence, wasNormalized);
            enrichedBlocks.add(enrichedBlock);
        }

        // Combine existing warnings with new ones
        List<DocumentWarning> allWarnings = new ArrayList<>(context.warnings());
        allWarnings.addAll(newWarnings);

        return context.withWarnings(allWarnings)
                .withWorkingBlocks(enrichedBlocks)
                .addStepMetric(getName(), StepMetric.builder()
                        .inputBlockCount(context.workingBlocks().size())
                        .outputBlockCount(enrichedBlocks.size())
                        .warnedCount(newWarnings.size())
                        .build());
    }

    /**
     * Normalizes section path based on source type.
     * This is the single source of truth for section path semantics.
     */
    private List<String> normalizeSectionPath(List<String> rawPath, DocumentSourceType sourceType) {
        if (rawPath == null || rawPath.isEmpty()) {
            return List.of();
        }

        return switch (sourceType) {
            case MARKDOWN, DOCX -> {
                // Markdown and DOCX have reliable heading hierarchies - preserve as-is
                yield List.copyOf(rawPath);
            }
            case PDF -> {
                // PDF: apply shallow truncation if enabled
                if (chunkingProperties.isPdfWeakSectionPathEnabled() && rawPath.size() > PDF_MAX_SECTION_DEPTH) {
                    yield rawPath.subList(0, PDF_MAX_SECTION_DEPTH);
                }
                yield List.copyOf(rawPath);
            }
            default -> List.copyOf(rawPath);
        };
    }

    private double calculateConfidence(DocumentSourceType sourceType, List<String> normalizedPath) {
        return switch (sourceType) {
            case MARKDOWN -> 1.0;
            case DOCX -> {
                // DOCX confidence based on path depth (deeper = more reliable structure)
                yield normalizedPath.isEmpty() ? 0.8 : 1.0;
            }
            case PDF -> {
                // PDF: low confidence for weak section path support
                if (normalizedPath.isEmpty()) {
                    yield 0.3;
                }
                // Shallow paths get slightly higher confidence
                yield 0.5;
            }
            default -> 0.5;
        };
    }

    private boolean isHeadingBlock(NormalizedBlock block) {
        return block.type() == NormalizedBlockType.TITLE || block.type() == NormalizedBlockType.HEADING;
    }

    private Integer getHeadingLevel(NormalizedBlock block) {
        Object level = block.metadata().get(HEADING_LEVEL_KEY);
        if (level instanceof Integer integer) {
            return integer;
        }
        if (level instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private NormalizedBlock enrichBlock(NormalizedBlock block, List<String> normalizedPath, double confidence, boolean wasNormalized) {
        Map<String, Object> enrichedMetadata = new LinkedHashMap<>(block.metadata());
        enrichedMetadata.put(SECTION_DEPTH_KEY, normalizedPath.size());
        enrichedMetadata.put(SECTION_PATH_CONFIDENCE_KEY, confidence);
        enrichedMetadata.put(SECTION_PATH_NORMALIZED_KEY, wasNormalized);

        // Build new block with normalized sectionPath
        return new NormalizedBlock(
                block.type(),
                block.text(),
                block.order(),
                block.pageNumber(),
                normalizedPath,  // Use normalized path, not parser's raw path
                enrichedMetadata
        );
    }

    private DocumentWarning createHeadingGapWarning(
            int blockOrder,
            int previousLevel,
            int currentLevel,
            String previousHeading,
            String currentHeading
    ) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("blockOrder", blockOrder);
        metadata.put("previousLevel", previousLevel);
        metadata.put("currentLevel", currentLevel);
        metadata.put("previousHeading", truncateHeading(previousHeading));
        metadata.put("currentHeading", truncateHeading(currentHeading));

        return DocumentWarning.builder()
                .code("HEADING_LEVEL_GAP")
                .category(DocumentWarningCategory.STRUCTURAL)
                .message(String.format(
                        "Heading level gap: %s (H%d) -> %s (H%d), missing H%d",
                        truncateHeading(previousHeading),
                        previousLevel,
                        truncateHeading(currentHeading),
                        currentLevel,
                        currentLevel - 1
                ))
                .metadata(metadata)
                .build();
    }

    private String truncateHeading(String heading) {
        if (heading == null) {
            return "";
        }
        return heading.length() <= 50 ? heading : heading.substring(0, 47) + "...";
    }
}