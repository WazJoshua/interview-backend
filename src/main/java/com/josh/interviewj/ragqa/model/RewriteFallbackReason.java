package com.josh.interviewj.ragqa.model;

public enum RewriteFallbackReason {
    NOT_ELIGIBLE,
    LLM_ERROR,
    TIMEOUT,
    PARSE_FAILED,
    NORMALIZED_EMPTY,
    EMPTY_RESULT,
    LENGTH_EXCEEDED,
    PRESERVED_TOKEN_MISSING,
    PROTECTED_TERM_MISSING,
    ANSWER_STYLE_DETECTED,
    EXCESSIVE_DRIFT
}
