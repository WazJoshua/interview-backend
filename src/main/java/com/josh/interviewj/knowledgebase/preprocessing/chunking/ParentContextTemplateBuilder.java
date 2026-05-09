package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds embedding text with parent context injection.
 */
@Component
public class ParentContextTemplateBuilder {

    private final ChunkingProperties properties;

    public ParentContextTemplateBuilder(DocumentPreprocessingProperties preprocessingProperties) {
        this.properties = preprocessingProperties.getChunking();
    }

    public String buildEmbeddingText(ChunkCandidate candidate) {
        StringBuilder prefix = new StringBuilder();

        String documentTitle = candidate.documentContext().documentTitle();
        if (documentTitle != null && !documentTitle.isBlank()) {
            prefix.append("[文档] ").append(truncate(documentTitle, 50)).append("\n");
        }

        List<String> sectionPath = candidate.sectionPath();
        if (!sectionPath.isEmpty()) {
            int maxLevels = properties.getParentContextMaxLevels();
            int startIndex = Math.max(0, sectionPath.size() - maxLevels);
            List<String> truncatedPath = sectionPath.subList(startIndex, sectionPath.size());
            if (!truncatedPath.isEmpty()) {
                prefix.append("[章节] ").append(String.join(" > ", truncatedPath)).append("\n");
            }
        }

        List<String> blockTypes = candidate.blockTypes();
        if (!blockTypes.isEmpty()) {
            String primaryType = blockTypes.get(0);
            if ("TABLE".equals(primaryType)) {
                prefix.append("[类型] 表格\n");
            } else if ("CODE".equals(primaryType)) {
                prefix.append("[类型] 代码");
                String codeLanguage = candidate.derivationContext().codeLanguage();
                if (codeLanguage != null && !codeLanguage.isBlank()) {
                    prefix.append(" (").append(codeLanguage).append(")");
                }
                prefix.append("\n");
            } else if ("LIST_ITEM".equals(primaryType)) {
                prefix.append("[类型] 列表\n");
            }
        }

        if (prefix.length() > properties.getParentContextMaxChars()) {
            prefix.setLength(properties.getParentContextMaxChars());
            int lastNewline = prefix.lastIndexOf("\n");
            if (lastNewline > 0) {
                prefix.setLength(lastNewline + 1);
            }
        }

        if (prefix.isEmpty()) {
            return candidate.displayText();
        }
        return prefix.toString() + "\n" + candidate.displayText();
    }

    public String buildEmbeddingText(ChunkCandidate candidate, String ignoredDocumentTitle) {
        return buildEmbeddingText(candidate);
    }

    public boolean hasParentContext(ChunkCandidate candidate) {
        if (candidate.documentContext().documentTitle() != null && !candidate.documentContext().documentTitle().isBlank()) {
            return true;
        }
        if (!candidate.sectionPath().isEmpty()) {
            return true;
        }
        List<String> blockTypes = candidate.blockTypes();
        if (blockTypes.isEmpty()) {
            return false;
        }
        String primaryType = blockTypes.get(0);
        return "TABLE".equals(primaryType) || "CODE".equals(primaryType) || "LIST_ITEM".equals(primaryType);
    }

    public boolean hasParentContext(ChunkCandidate candidate, String ignoredDocumentTitle) {
        return hasParentContext(candidate);
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
