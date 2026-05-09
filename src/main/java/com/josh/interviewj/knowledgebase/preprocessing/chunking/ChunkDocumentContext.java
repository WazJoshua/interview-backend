package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import lombok.Builder;

/**
 * Document-level context shared by chunk candidates from the same source.
 */
@Builder(toBuilder = true)
public record ChunkDocumentContext(
        String sourceType,
        String documentTitle,
        String fileName,
        String preprocessingVersion
) {

    public ChunkDocumentContext {
        sourceType = sourceType == null ? "" : sourceType;
        fileName = fileName == null ? "" : fileName;
        preprocessingVersion = preprocessingVersion == null ? "" : preprocessingVersion;
        documentTitle = documentTitle == null || documentTitle.isBlank() ? null : documentTitle;
    }
}
