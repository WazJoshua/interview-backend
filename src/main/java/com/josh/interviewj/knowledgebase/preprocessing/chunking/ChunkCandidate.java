package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed chunk contract shared by structure-aware and fixed-size chunking paths.
 */
@Builder(toBuilder = true)
public record ChunkCandidate(
        int chunkIndex,
        List<Integer> blockOrders,
        String bodyText,
        String displayText,
        String embeddingText,
        int tokenCountEstimate,
        boolean hasParentContext,
        ChunkDocumentContext documentContext,
        ChunkSemanticContext semanticContext,
        ChunkDerivationContext derivationContext
) {

    public ChunkCandidate {
        blockOrders = blockOrders == null ? List.of() : List.copyOf(blockOrders);
        bodyText = bodyText == null ? "" : bodyText;
        displayText = displayText == null ? "" : displayText;
        embeddingText = embeddingText == null ? "" : embeddingText;
        documentContext = documentContext == null ? ChunkDocumentContext.builder().build() : documentContext;
        semanticContext = semanticContext == null ? ChunkSemanticContext.builder().build() : semanticContext;
        derivationContext = derivationContext == null ? ChunkDerivationContext.builder().build() : derivationContext;
    }

    public List<String> blockTypes() {
        return semanticContext.blockTypes();
    }

    public List<String> sectionPath() {
        return semanticContext.sectionPath();
    }

    public List<Integer> pageNumbers() {
        return semanticContext.pageNumbers();
    }

    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "documentTitle", documentContext.documentTitle());
        putIfNotBlank(metadata, "sourceType", documentContext.sourceType());
        metadata.put("chunkVersion", "STRUCTURE_AWARE_V1");
        metadata.put("hasParentContext", hasParentContext);
        putIfPresent(metadata, "tablePartIndex", derivationContext.tablePartIndex());
        putIfPresent(metadata, "tablePartCount", derivationContext.tablePartCount());
        putIfPresent(metadata, "codeSegmentIndex", derivationContext.codeSegmentIndex());
        putIfPresent(metadata, "codeSegmentCount", derivationContext.codeSegmentCount());
        putIfNotBlank(metadata, "language", derivationContext.codeLanguage());
        return Map.copyOf(metadata);
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private static void putIfNotBlank(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    public static class ChunkCandidateBuilder {

        public ChunkCandidateBuilder blockTypes(List<String> blockTypes) {
            ChunkSemanticContext current = semanticContext == null ? ChunkSemanticContext.builder().build() : semanticContext;
            this.semanticContext = current.toBuilder().blockTypes(blockTypes).build();
            return this;
        }

        public ChunkCandidateBuilder sectionPath(List<String> sectionPath) {
            ChunkSemanticContext current = semanticContext == null ? ChunkSemanticContext.builder().build() : semanticContext;
            this.semanticContext = current.toBuilder().sectionPath(sectionPath).build();
            return this;
        }

        public ChunkCandidateBuilder pageNumbers(List<Integer> pageNumbers) {
            ChunkSemanticContext current = semanticContext == null ? ChunkSemanticContext.builder().build() : semanticContext;
            this.semanticContext = current.toBuilder().pageNumbers(pageNumbers).build();
            return this;
        }

        public ChunkCandidateBuilder metadata(Map<String, Object> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return this;
            }
            ChunkDocumentContext currentDocument = documentContext == null ? ChunkDocumentContext.builder().build() : documentContext;
            ChunkDerivationContext currentDerivation = derivationContext == null ? ChunkDerivationContext.builder().build() : derivationContext;
            Object documentTitle = metadata.get("documentTitle");
            Object sourceType = metadata.get("sourceType");
            Object hasParentContext = metadata.get("hasParentContext");
            Object tablePartIndex = metadata.get("tablePartIndex");
            Object tablePartCount = metadata.get("tablePartCount");
            Object codeSegmentIndex = metadata.get("codeSegmentIndex");
            Object codeSegmentCount = metadata.get("codeSegmentCount");
            Object language = metadata.get("language");

            this.documentContext = currentDocument.toBuilder()
                    .documentTitle(documentTitle == null ? currentDocument.documentTitle() : String.valueOf(documentTitle))
                    .sourceType(sourceType == null ? currentDocument.sourceType() : String.valueOf(sourceType))
                    .build();
            this.derivationContext = currentDerivation.toBuilder()
                    .tablePartIndex(asInteger(tablePartIndex, currentDerivation.tablePartIndex()))
                    .tablePartCount(asInteger(tablePartCount, currentDerivation.tablePartCount()))
                    .codeSegmentIndex(asInteger(codeSegmentIndex, currentDerivation.codeSegmentIndex()))
                    .codeSegmentCount(asInteger(codeSegmentCount, currentDerivation.codeSegmentCount()))
                    .codeLanguage(language == null ? currentDerivation.codeLanguage() : String.valueOf(language))
                    .build();
            if (hasParentContext instanceof Boolean booleanValue) {
                this.hasParentContext = booleanValue;
            }
            return this;
        }

        private Integer asInteger(Object value, Integer fallback) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return fallback;
        }
    }
}
