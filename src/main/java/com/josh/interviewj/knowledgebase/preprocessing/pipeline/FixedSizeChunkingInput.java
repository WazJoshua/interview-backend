package com.josh.interviewj.knowledgebase.preprocessing.pipeline;

import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDisposition;
import com.josh.interviewj.knowledgebase.preprocessing.lowsignal.RetrievalDispositionReasonCode;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import lombok.Builder;

import java.util.List;

@Builder
public record FixedSizeChunkingInput(
        String normalizedTextForChunking,
        List<RetainedSegment> retainedSegments
) {

    public FixedSizeChunkingInput {
        normalizedTextForChunking = normalizedTextForChunking == null ? "" : normalizedTextForChunking;
        retainedSegments = retainedSegments == null ? List.of() : List.copyOf(retainedSegments);
    }

    @Builder
    public record RetainedSegment(
            int blockOrder,
            int startOffset,
            int endOffset,
            Integer pageNumber,
            NormalizedBlockType blockType,
            RetrievalDisposition retrievalDisposition,
            List<RetrievalDispositionReasonCode> retrievalDispositionReasonCodes,
            List<String> retrievalDispositionEvidence,
            List<String> qualityFlags,
            List<String> preprocessingWarnings
    ) {

        public RetainedSegment {
            retrievalDisposition = retrievalDisposition == null ? RetrievalDisposition.KEEP : retrievalDisposition;
            retrievalDispositionReasonCodes = retrievalDispositionReasonCodes == null
                    ? List.of()
                    : List.copyOf(retrievalDispositionReasonCodes);
            retrievalDispositionEvidence = retrievalDispositionEvidence == null
                    ? List.of()
                    : List.copyOf(retrievalDispositionEvidence);
            qualityFlags = qualityFlags == null ? List.of() : List.copyOf(qualityFlags);
            preprocessingWarnings = preprocessingWarnings == null ? List.of() : List.copyOf(preprocessingWarnings);
        }
    }
}
