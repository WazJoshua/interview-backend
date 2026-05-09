package com.josh.interviewj.knowledgebase.service;

import lombok.Getter;

/**
 * Wraps a non-terminal ingestion failure with stage and safe summary information.
 */
@Getter
public class KbIngestionStageException extends RuntimeException {

    private final String stage;
    private final String safeSummary;

    public KbIngestionStageException(String stage, String safeSummary, Throwable cause) {
        super(safeSummary, cause);
        this.stage = stage == null ? "UNKNOWN" : stage;
        this.safeSummary = safeSummary == null || safeSummary.isBlank()
                ? "知识库文档处理失败，请稍后重试"
                : safeSummary;
    }
}
