package com.josh.interviewj.knowledgebase.preprocessing.lowsignal;

import lombok.Builder;

import java.util.List;

@Builder
public record LowSignalBlockDecision(
        RetrievalDisposition retrievalDisposition,
        List<RetrievalDispositionReasonCode> retrievalDispositionReasonCodes,
        List<String> retrievalDispositionEvidence,
        LowSignalDecisionType legacyDecisionType,
        List<LowSignalReasonCode> legacyReasonCodes,
        double score
) {

    public LowSignalBlockDecision {
        retrievalDisposition = retrievalDisposition == null ? RetrievalDisposition.KEEP : retrievalDisposition;
        retrievalDispositionReasonCodes = retrievalDispositionReasonCodes == null
                ? List.of()
                : List.copyOf(retrievalDispositionReasonCodes);
        retrievalDispositionEvidence = retrievalDispositionEvidence == null
                ? List.of()
                : List.copyOf(retrievalDispositionEvidence);
        LowSignalDecisionType expectedLegacyDecisionType = LowSignalDecisionType.fromRetrievalDisposition(retrievalDisposition);
        if (legacyDecisionType != null && legacyDecisionType != expectedLegacyDecisionType) {
            throw new IllegalArgumentException("legacyDecisionType must match retrievalDisposition");
        }
        legacyDecisionType = legacyDecisionType == null ? expectedLegacyDecisionType : legacyDecisionType;
        legacyReasonCodes = legacyReasonCodes == null ? List.of() : List.copyOf(legacyReasonCodes);
    }

    public static LowSignalBlockDecision fromLegacyDecision(
            LowSignalDecisionType legacyDecisionType,
            List<LowSignalReasonCode> legacyReasonCodes,
            double score
    ) {
        List<LowSignalReasonCode> copiedLegacyReasonCodes = legacyReasonCodes == null
                ? List.of()
                : List.copyOf(legacyReasonCodes);
        List<RetrievalDispositionReasonCode> canonicalReasonCodes =
                RetrievalDispositionReasonCode.fromLegacyReasonCodes(copiedLegacyReasonCodes);
        List<String> evidence = canonicalReasonCodes.stream()
                .map(reasonCode -> "reason:" + reasonCode.name())
                .toList();
        return LowSignalBlockDecision.builder()
                .retrievalDisposition(toRetrievalDisposition(legacyDecisionType))
                .retrievalDispositionReasonCodes(canonicalReasonCodes)
                .retrievalDispositionEvidence(evidence)
                .legacyDecisionType(legacyDecisionType)
                .legacyReasonCodes(copiedLegacyReasonCodes)
                .score(score)
                .build();
    }

    public LowSignalDecisionType decisionType() {
        return legacyDecisionType;
    }

    public List<LowSignalReasonCode> reasonCodes() {
        return legacyReasonCodes;
    }

    private static RetrievalDisposition toRetrievalDisposition(LowSignalDecisionType legacyDecisionType) {
        if (legacyDecisionType == null) {
            return RetrievalDisposition.KEEP;
        }
        return switch (legacyDecisionType) {
            case PROTECT -> RetrievalDisposition.PROTECT;
            case KEEP -> RetrievalDisposition.KEEP;
            case WARN -> RetrievalDisposition.SOFT_DEINDEX;
            case DROP -> RetrievalDisposition.DROP;
        };
    }
}
