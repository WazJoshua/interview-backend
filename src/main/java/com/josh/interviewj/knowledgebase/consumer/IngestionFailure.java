package com.josh.interviewj.knowledgebase.consumer;

public record IngestionFailure(
        IngestionFailureCategory category,
        IngestionStage stage,
        String safeMessage
) {

    public IngestionFailure {
        stage = stage == null ? IngestionStage.UNKNOWN : stage;
        safeMessage = safeMessage == null || safeMessage.isBlank()
                ? "知识库文档处理失败，请稍后重试"
                : safeMessage;
    }
}
