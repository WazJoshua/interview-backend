package com.josh.interviewj.knowledgebase.preprocessing.lowsignal;

public enum LowSignalDecisionType {
    PROTECT,
    KEEP,
    WARN,
    DROP;

    public static LowSignalDecisionType fromRetrievalDisposition(RetrievalDisposition retrievalDisposition) {
        if (retrievalDisposition == null) {
            return KEEP;
        }
        return switch (retrievalDisposition) {
            case PROTECT -> PROTECT;
            case KEEP -> KEEP;
            case SOFT_DEINDEX -> WARN;
            case DROP -> DROP;
        };
    }
}
