package com.josh.interviewj.llm.core;

public class LlmException extends RuntimeException {

    private final boolean retryable;
    private final String reason;

    public LlmException(String message, String reason, boolean retryable, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    public LlmException(String message, String reason, boolean retryable) {
        super(message);
        this.reason = reason;
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getReason() {
        return reason;
    }
}
