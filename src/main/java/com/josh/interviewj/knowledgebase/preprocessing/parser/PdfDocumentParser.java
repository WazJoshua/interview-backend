package com.josh.interviewj.knowledgebase.preprocessing.parser;

import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarning;
import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentWarningCategory;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentPreprocessingException;
import lombok.Getter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class PdfDocumentParser implements DocumentParser {

    private static final float COLUMN_CENTER_GAP = 120F;
    private static final float READING_ORDER_Y_JUMP = 24F;
    private static final int MIN_LAYOUT_LINES = 8;
    private static final int MAX_APPENDIX_HEADING_LENGTH = 40;

    @Override
    public boolean supports(String fileType, String fileName) {
        String normalizedType = safeLower(fileType);
        String normalizedName = safeLower(fileName);
        return normalizedType.contains("pdf") || normalizedName.endsWith(".pdf");
    }

    @Override
    public ParsedDocument parse(Path filePath, String fileType, String fileName) {
        try (PDDocument document = PDDocument.load(filePath.toFile())) {
            PageLineStripper stripper = new PageLineStripper();
            stripper.getText(document);
            List<ParsedBlock> blocks = new ArrayList<>();
            List<DocumentWarning> warnings = buildWarnings(stripper.getPages());
            int order = 0;
            String title = null;
            List<String> activeSectionPath = List.of();
            for (PageLines page : stripper.getPages()) {
                List<PageLine> currentParagraph = new ArrayList<>();
                for (PageLine line : page.lines()) {
                    if (line.text().isBlank()) {
                        FlushResult flushResult = flushParagraph(
                                currentParagraph,
                                page.pageNumber(),
                                order,
                                title == null,
                                activeSectionPath
                        );
                        if (flushResult.block() != null) {
                            blocks.add(flushResult.block());
                            order++;
                            activeSectionPath = flushResult.nextSectionPath();
                            if (title == null && flushResult.block().type() == ParsedBlockType.TITLE) {
                                title = flushResult.block().text();
                            }
                        }
                        continue;
                    }
                    currentParagraph.add(line);
                }
                FlushResult flushResult = flushParagraph(
                        currentParagraph,
                        page.pageNumber(),
                        order,
                        title == null,
                        activeSectionPath
                );
                if (flushResult.block() != null) {
                    blocks.add(flushResult.block());
                    order++;
                    activeSectionPath = flushResult.nextSectionPath();
                    if (title == null && flushResult.block().type() == ParsedBlockType.TITLE) {
                        title = flushResult.block().text();
                    }
                }
            }

            return ParsedDocument.builder()
                    .sourceType(DocumentSourceType.PDF)
                    .fileName(fileName)
                    .title(title)
                    .rawMetadata(Map.of("pageCount", document.getNumberOfPages(), "parser", "pdfbox-lines"))
                    .blocks(blocks)
                    .warnings(warnings)
                    .build();
        } catch (IOException ex) {
            throw new DocumentPreprocessingException("PDF parse failed", ex);
        }
    }

    private FlushResult flushParagraph(
            List<PageLine> lines,
            int pageNumber,
            int order,
            boolean canBeTitle,
            List<String> activeSectionPath
    ) {
        if (lines.isEmpty()) {
            return new FlushResult(null, activeSectionPath);
        }
        String text = lines.stream().map(PageLine::text).reduce((left, right) -> left + "\n" + right).orElse("");
        PageLine firstLine = lines.get(0);
        ParsedBlockType type = canBeTitle && lines.size() == 1 && text.length() <= 120
                ? ParsedBlockType.TITLE
                : ParsedBlockType.PARAGRAPH;
        String appendixHeading = resolveAppendixHeading(firstLine.text());
        List<String> sectionPath = appendixHeading == null ? activeSectionPath : appendSectionPath(activeSectionPath, appendixHeading);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("lineCount", lines.size());
        metadata.put("minX", firstLine.startX());
        metadata.put("maxX", lines.stream().map(PageLine::startX).max(Float::compare).orElse(firstLine.startX()));
        metadata.put("lineTexts", lines.stream().map(PageLine::text).toList());
        ParsedBlock block = ParsedBlock.builder()
                .type(type)
                .text(text.trim())
                .order(order)
                .pageNumber(pageNumber)
                .sectionPath(sectionPath)
                .metadata(metadata)
                .build();
        lines.clear();
        return new FlushResult(block, appendixHeading == null ? activeSectionPath : sectionPath);
    }

    private String resolveAppendixHeading(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_APPENDIX_HEADING_LENGTH) {
            return null;
        }
        String normalized = safeLower(trimmed);
        if (trimmed.contains("=") || trimmed.contains("{") || trimmed.contains("}") || normalized.contains("://")) {
            return null;
        }
        if (normalized.startsWith("appendix") || normalized.startsWith("附录")) {
            return trimmed;
        }
        return null;
    }

    private List<String> appendSectionPath(List<String> currentPath, String heading) {
        if (currentPath.isEmpty()) {
            return List.of(heading);
        }
        if (heading.equals(currentPath.get(currentPath.size() - 1))) {
            return currentPath;
        }
        List<String> updatedPath = new ArrayList<>(currentPath);
        updatedPath.add(heading);
        return List.copyOf(updatedPath);
    }

    private List<DocumentWarning> buildWarnings(List<PageLines> pages) {
        Set<String> warningCodes = new LinkedHashSet<>();
        for (PageLines page : pages) {
            List<PageLine> nonBlankLines = page.lines().stream().filter(line -> !line.text().isBlank()).toList();
            if (nonBlankLines.size() < MIN_LAYOUT_LINES) {
                continue;
            }
            List<Float> startXs = nonBlankLines.stream().map(PageLine::startX).sorted().toList();
            float minX = startXs.get(0);
            float maxX = startXs.get(startXs.size() - 1);
            long rightBandCount = nonBlankLines.stream().filter(line -> line.startX() >= minX + COLUMN_CENTER_GAP).count();
            long leftBandCount = nonBlankLines.size() - rightBandCount;
            if ((maxX - minX) >= COLUMN_CENTER_GAP
                    && leftBandCount >= Math.ceil(nonBlankLines.size() * 0.25D)
                    && rightBandCount >= Math.ceil(nonBlankLines.size() * 0.25D)) {
                warningCodes.add("POSSIBLE_MULTI_COLUMN_LAYOUT");
            }

            int yReverseJumpCount = 0;
            for (int index = 1; index < nonBlankLines.size(); index++) {
                float previousY = nonBlankLines.get(index - 1).startY();
                float currentY = nonBlankLines.get(index).startY();
                if ((currentY - previousY) >= READING_ORDER_Y_JUMP) {
                    yReverseJumpCount++;
                }
            }
            if (yReverseJumpCount >= 2) {
                warningCodes.add("POSSIBLE_READING_ORDER_ANOMALY");
            }

            int alternatingTransitions = 0;
            Boolean previousRightBand = null;
            for (PageLine line : nonBlankLines) {
                boolean currentRightBand = line.startX() >= minX + COLUMN_CENTER_GAP;
                if (previousRightBand != null && previousRightBand != currentRightBand) {
                    alternatingTransitions++;
                }
                previousRightBand = currentRightBand;
            }
            if (alternatingTransitions >= 4) {
                warningCodes.add("POSSIBLE_ALTERNATING_COLUMNS");
            }
        }

        return warningCodes.stream()
                .sorted()
                .map(code -> DocumentWarning.builder()
                        .code(code)
                        .category(DocumentWarningCategory.STRUCTURAL)
                        .message(code)
                        .build())
                .toList();
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static final class PageLineStripper extends PDFTextStripper {

        @Getter
        private final List<PageLines> pages = new ArrayList<>();

        private final List<PageLine> currentLines = new ArrayList<>();
        private final StringBuilder currentText = new StringBuilder();
        private float currentY = Float.NaN;
        private float currentStartX = Float.NaN;

        private PageLineStripper() throws IOException {
            setSortByPosition(true);
        }

        @Override
        protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
            super.startPage(page);
            currentLines.clear();
            currentText.setLength(0);
            currentY = Float.NaN;
            currentStartX = Float.NaN;
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (Float.isNaN(currentY)) {
                currentY = text.getYDirAdj();
                currentStartX = text.getXDirAdj();
            } else if (Math.abs(text.getYDirAdj() - currentY) > 2.0F) {
                flushLine();
                currentY = text.getYDirAdj();
                currentStartX = text.getXDirAdj();
            }
            currentText.append(text.getUnicode());
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            flushLine();
        }

        @Override
        protected void endPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
            flushLine();
            pages.add(new PageLines(getCurrentPageNo(), new ArrayList<>(currentLines)));
            currentLines.clear();
            super.endPage(page);
        }

        private void flushLine() {
            if (currentText.length() == 0 && Float.isNaN(currentY)) {
                return;
            }
            currentLines.add(new PageLine(currentText.toString().stripTrailing(), currentStartX, currentY));
            currentText.setLength(0);
            currentY = Float.NaN;
            currentStartX = Float.NaN;
        }
    }

    private record PageLines(int pageNumber, List<PageLine> lines) {
    }

    private record PageLine(String text, float startX, float startY) {
    }

    private record FlushResult(ParsedBlock block, List<String> nextSectionPath) {
    }
}
