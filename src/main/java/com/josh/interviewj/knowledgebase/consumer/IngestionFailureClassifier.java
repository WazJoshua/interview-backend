package com.josh.interviewj.knowledgebase.consumer;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.service.KbDocumentIngestionService;
import com.josh.interviewj.knowledgebase.service.KbIngestionStageException;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.sql.SQLTransientException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

@Component
public class IngestionFailureClassifier {

    private static final String INTERNAL_BUG_MESSAGE = "知识库文档处理内部错误，请联系开发者排查";
    private static final String RETRYABLE_MESSAGE = "知识库文档处理失败，请稍后重试";

    public IngestionFailure classify(Exception exception) {
        if (exception instanceof KbDocumentIngestionService.TerminalIngestionException terminal) {
            return new IngestionFailure(
                    IngestionFailureCategory.CONTENT_TERMINAL,
                    IngestionStage.UNKNOWN,
                    terminal.getMessage()
            );
        }
        if (exception instanceof KbIngestionStageException stageException) {
            IngestionStage stage = IngestionStage.fromValue(stageException.getStage());
            Throwable cause = stageException.getCause();
            if (isInternalBug(cause)) {
                return new IngestionFailure(IngestionFailureCategory.INTERNAL_BUG, stage, INTERNAL_BUG_MESSAGE);
            }
            if (isRetryableInfra(stageException) || isRetryableInfra(cause)) {
                return new IngestionFailure(IngestionFailureCategory.INFRA_RETRYABLE, stage, stageException.getSafeSummary());
            }
            return new IngestionFailure(IngestionFailureCategory.INFRA_RETRYABLE, stage, stageException.getSafeSummary());
        }
        if (isInternalBug(exception)) {
            return new IngestionFailure(IngestionFailureCategory.INTERNAL_BUG, IngestionStage.UNKNOWN, INTERNAL_BUG_MESSAGE);
        }
        if (isRetryableInfra(exception)) {
            return new IngestionFailure(IngestionFailureCategory.INFRA_RETRYABLE, IngestionStage.UNKNOWN, safeMessage(exception));
        }
        return new IngestionFailure(IngestionFailureCategory.INFRA_RETRYABLE, IngestionStage.UNKNOWN, safeMessage(exception));
    }

    private boolean isInternalBug(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof NullPointerException || throwable instanceof IllegalStateException) {
            return true;
        }
        String message = throwable.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("contract violation");
    }

    private boolean isRetryableInfra(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof TimeoutException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof SQLTransientException) {
            return true;
        }
        if (throwable instanceof BusinessException businessException) {
            return ErrorCode.LLM_001.equals(businessException.getErrorCode())
                    || ErrorCode.FILE_001.equals(businessException.getErrorCode());
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("timeout")
                || lower.contains("too many requests")
                || lower.contains("429")
                || lower.contains("redis")
                || lower.contains("connection reset")
                || lower.contains("temporarily unavailable");
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return RETRYABLE_MESSAGE;
        }
        return throwable.getMessage();
    }
}
