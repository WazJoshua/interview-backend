package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import com.josh.interviewj.knowledgebase.preprocessing.pipeline.FixedSizeChunkingInput;
import lombok.Builder;

import java.util.List;

/**
 * Holds chunk derivation details needed by persistence assembly but not by core chunking semantics.
 */
@Builder(toBuilder = true)
public record ChunkDerivationContext(
        Integer tablePartIndex,
        Integer tablePartCount,
        Integer codeSegmentIndex,
        Integer codeSegmentCount,
        String codeLanguage,
        Integer startPosition,
        Integer endPosition,
        List<FixedSizeChunkingInput.RetainedSegment> retainedSegments
) {

    public ChunkDerivationContext {
        codeLanguage = codeLanguage == null || codeLanguage.isBlank() ? null : codeLanguage;
        retainedSegments = retainedSegments == null ? List.of() : List.copyOf(retainedSegments);
    }
}
