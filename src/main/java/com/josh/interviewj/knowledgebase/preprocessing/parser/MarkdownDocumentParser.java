package com.josh.interviewj.knowledgebase.preprocessing.parser;

import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.model.ParsedDocument;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.DocumentPreprocessingException;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.BulletListItem;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.OrderedListItem;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TableSeparator;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MarkdownDocumentParser implements DocumentParser {

    private final Parser parser = Parser.builder()
            .extensions(List.of(com.vladsch.flexmark.ext.tables.TablesExtension.create()))
            .build();

    @Override
    public boolean supports(String fileType, String fileName) {
        String normalizedType = safeLower(fileType);
        String normalizedName = safeLower(fileName);
        return normalizedType.contains("markdown")
                || normalizedName.endsWith(".md")
                || normalizedName.endsWith(".markdown");
    }

    @Override
    public ParsedDocument parse(Path filePath, String fileType, String fileName) {
        try {
            Document document = parser.parse(Files.readString(filePath));
            List<ParsedBlock> blocks = new ArrayList<>();
            collectBlocks(document.getFirstChild(), blocks, List.of());
            String title = blocks.stream()
                    .filter(block -> block.type() == ParsedBlockType.TITLE || block.type() == ParsedBlockType.HEADING)
                    .map(ParsedBlock::text)
                    .findFirst()
                    .orElse(null);
            return ParsedDocument.builder()
                    .sourceType(DocumentSourceType.MARKDOWN)
                    .fileName(fileName)
                    .title(title)
                    .rawMetadata(Map.of("parser", "markdown-ast"))
                    .blocks(blocks)
                    .build();
        } catch (IOException ex) {
            throw new DocumentPreprocessingException("Markdown parse failed", ex);
        }
    }

    private void collectBlocks(Node node, List<ParsedBlock> blocks, List<String> currentSectionPath) {
        List<String> activeSectionPath = List.copyOf(currentSectionPath);
        for (Node current = node; current != null; current = current.getNext()) {
            if (current instanceof Heading heading) {
                activeSectionPath = updateSectionPath(activeSectionPath, heading.getLevel(), extractNodeText(current));
                ParsedBlock block = toBlock(current, blocks.size(), activeSectionPath);
                if (block != null) {
                    blocks.add(block);
                }
                continue;
            }
            if (isContainerNode(current)) {
                collectBlocks(current.getFirstChild(), blocks, activeSectionPath);
                continue;
            }
            ParsedBlock block = toBlock(current, blocks.size(), activeSectionPath);
            if (block != null) {
                blocks.add(block);
                continue;
            }
        }
    }

    private boolean isContainerNode(Node node) {
        return node instanceof BulletList
                || node instanceof OrderedList
                || node instanceof TableHead
                || node instanceof TableSeparator
                || node instanceof TableBody
                || node instanceof TableRow
                || node instanceof TableCell;
    }

    private ParsedBlock toBlock(Node node, int order, List<String> sectionPath) {
        if (node instanceof Heading heading) {
            ParsedBlockType type = heading.getLevel() == 1 ? ParsedBlockType.TITLE : ParsedBlockType.HEADING;
            return block(type, extractNodeText(node), order, sectionPath, Map.of("headingLevel", heading.getLevel()));
        }
        if (node instanceof Paragraph) {
            return block(ParsedBlockType.PARAGRAPH, extractNodeText(node), order, sectionPath, Map.of());
        }
        if (node instanceof BulletListItem || node instanceof OrderedListItem) {
            return block(ParsedBlockType.LIST_ITEM, extractNodeText(node), order, sectionPath, Map.of());
        }
        if (node instanceof FencedCodeBlock codeBlock) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (codeBlock.getInfo().isNotNull()) {
                metadata.put("language", codeBlock.getInfo().toString().trim());
            }
            return block(ParsedBlockType.CODE, codeBlock.getContentChars().normalizeEOL().toString().strip(), order, sectionPath, metadata);
        }
        if (node instanceof BlockQuote) {
            return block(ParsedBlockType.QUOTE, extractNodeText(node), order, sectionPath, Map.of());
        }
        if (node instanceof TableBlock) {
            return block(ParsedBlockType.TABLE, extractNodeText(node), order, sectionPath, Map.of());
        }
        if (node instanceof ThematicBreak) {
            return block(ParsedBlockType.UNKNOWN, "---", order, sectionPath, Map.of("thematicBreak", true));
        }
        String text = node.getChars().toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        return block(ParsedBlockType.UNKNOWN, text, order, sectionPath, Map.of());
    }

    private ParsedBlock block(ParsedBlockType type, String text, int order, List<String> sectionPath, Map<String, Object> metadata) {
        return ParsedBlock.builder()
                .type(type)
                .text(text)
                .order(order)
                .sectionPath(sectionPath)
                .metadata(metadata)
                .build();
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

    private String extractNodeText(Node node) {
        String text = node.getChars().toString();
        if (node instanceof Heading) {
            return text.replaceFirst("^#+\\s*", "").trim();
        }
        if (node instanceof BulletListItem) {
            return text.replaceFirst("^[-*+]\\s*", "").trim();
        }
        if (node instanceof OrderedListItem) {
            return text.replaceFirst("^\\d+[.)]\\s*", "").trim();
        }
        if (node instanceof BlockQuote) {
            return text.replaceFirst("^>+\\s*", "").trim();
        }
        return text.trim();
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
