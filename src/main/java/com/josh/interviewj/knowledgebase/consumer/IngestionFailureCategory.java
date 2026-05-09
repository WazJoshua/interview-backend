package com.josh.interviewj.knowledgebase.consumer;

public enum IngestionFailureCategory {
    CONTENT_TERMINAL,
    INFRA_RETRYABLE,
    INTERNAL_BUG
}
