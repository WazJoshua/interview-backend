package com.josh.interviewj.knowledgebase.preprocessing.parser;

import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentPreprocessingException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DocxDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileType, String fileName) {
        String normalizedType = safeLower(fileType);
        String normalizedName = safeLower(fileName);
        return normalizedType.contains("officedocument")
                || normalizedName.endsWith(".docx");
    }

    @Override
    public ParsedDocument parse(Path filePath, String fileType, String fileName) {
        try (InputStream inputStream = Files.newInputStream(filePath);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            List<ParsedBlock> blocks = new ArrayList<>();
            int order = 0;
            String title = null;
            List<String> activeSectionPath = List.of();
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (bodyElement instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText() == null ? "" : paragraph.getText().trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    ParsedBlockType type = resolveParagraphType(paragraph, text, title == null);
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    Integer headingLevel = resolveHeadingLevel(paragraph.getStyle());
                    if (paragraph.getStyle() != null) {
                        metadata.put("style", paragraph.getStyle());
                    }
                    if (headingLevel != null) {
                        metadata.put("headingLevel", headingLevel);
                    }
                    metadata.put("styleRole", isHeadingLike(paragraph.getStyle()) ? "heading" : "body");
                    List<String> sectionPath = activeSectionPath;
                    if (type == ParsedBlockType.TITLE || type == ParsedBlockType.HEADING) {
                        int resolvedHeadingLevel = headingLevel == null ? 1 : headingLevel;
                        sectionPath = updateSectionPath(activeSectionPath, resolvedHeadingLevel, text);
                        activeSectionPath = sectionPath;
                    }
                    ParsedBlock block = ParsedBlock.builder()
                            .type(type)
                            .text(text)
                            .order(order++)
                            .sectionPath(sectionPath)
                            .metadata(metadata)
                            .build();
                    blocks.add(block);
                    if (title == null && (type == ParsedBlockType.TITLE || type == ParsedBlockType.HEADING)) {
                        title = text;
                    }
                    continue;
                }
                if (bodyElement instanceof XWPFTable table) {
                    StringBuilder builder = new StringBuilder();
                    for (XWPFTableRow row : table.getRows()) {
                        String rowText = row.getTableCells().stream()
                                .map(cell -> cell.getText() == null ? "" : cell.getText().trim())
                                .filter(value -> !value.isEmpty())
                                .reduce((left, right) -> left + " | " + right)
                                .orElse("");
                        if (!rowText.isEmpty()) {
                            if (builder.length() > 0) {
                                builder.append('\n');
                            }
                            builder.append(rowText);
                        }
                    }
                    if (builder.length() == 0) {
                        continue;
                    }
                    blocks.add(ParsedBlock.builder()
                            .type(ParsedBlockType.TABLE)
                            .text(builder.toString())
                            .order(order++)
                            .sectionPath(activeSectionPath)
                            .metadata(Map.of("rows", table.getRows().size()))
                            .build());
                }
            }
            return ParsedDocument.builder()
                    .sourceType(DocumentSourceType.DOCX)
                    .fileName(fileName)
                    .title(title)
                    .rawMetadata(Map.of("parser", "docx-xwpf"))
                    .blocks(blocks)
                    .build();
        } catch (IOException ex) {
            throw new DocumentPreprocessingException("DOCX parse failed", ex);
        }
    }

    private ParsedBlockType resolveParagraphType(XWPFParagraph paragraph, String text, boolean canBeTitle) {
        String style = safeLower(paragraph.getStyle());
        if (style.startsWith("heading") || style.contains("title")) {
            return canBeTitle ? ParsedBlockType.TITLE : ParsedBlockType.HEADING;
        }
        if (paragraph.getNumID() != null || text.startsWith("- ") || text.startsWith("* ")) {
            return ParsedBlockType.LIST_ITEM;
        }
        return ParsedBlockType.PARAGRAPH;
    }

    private boolean isHeadingLike(String style) {
        String normalizedStyle = safeLower(style);
        return normalizedStyle.startsWith("heading") || normalizedStyle.contains("title");
    }

    private Integer resolveHeadingLevel(String style) {
        String normalizedStyle = safeLower(style);
        if (normalizedStyle.startsWith("heading")) {
            String suffix = normalizedStyle.substring("heading".length());
            if (suffix.chars().allMatch(Character::isDigit) && !suffix.isBlank()) {
                return Integer.parseInt(suffix);
            }
            return 1;
        }
        if (normalizedStyle.contains("title")) {
            return 1;
        }
        return null;
    }

    private List<String> updateSectionPath(List<String> currentPath, int headingLevel, String headingText) {
        List<String> updatedPath = new ArrayList<>(currentPath);
        int desiredSize = Math.max(0, headingLevel - 1);
        while (updatedPath.size() > desiredSize) {
            updatedPath.remove(updatedPath.size() - 1);
        }
        updatedPath.add(headingText);
        return List.copyOf(updatedPath);
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
