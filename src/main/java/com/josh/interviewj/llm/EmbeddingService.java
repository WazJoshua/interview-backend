package com.josh.interviewj.llm;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.EmbeddingClient;
import com.josh.interviewj.llm.core.EmbeddingRequest;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.provider.TemplateAwareEmbeddingExecutor;
import com.josh.interviewj.llm.routing.EmbeddingRoute;
import com.josh.interviewj.llm.routing.EmbeddingRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmbeddingService implements EmbeddingClient {

    private final EmbeddingRouter embeddingRouter;
    private final TemplateAwareEmbeddingExecutor templateAwareEmbeddingExecutor;

    @Override
    public EmbeddingResponse generate(EmbeddingRequest request) {
        if (request == null || request.purpose() == null) {
            throw new BusinessException(ErrorCode.LLM_001, "Embedding purpose is required");
        }
        if (request.input() == null || request.input().isBlank()) {
            throw new BusinessException(ErrorCode.LLM_001, "Embedding input is required");
        }

        EmbeddingRoute route = embeddingRouter.resolve(request.purpose());
        LlmProperties.ProviderProperties providerConfig = route.providerConfig();
        if (providerConfig.getApiKey() == null || providerConfig.getApiKey().isBlank()) {
            throw new BusinessException(ErrorCode.LLM_001, "Embedding api key is not configured for provider: " + route.providerName());
        }

        int attempts = Math.max(1, providerConfig.getMaxRetries());
        Duration backoff = Duration.ofMillis(Math.max(1, providerConfig.getRetryBackoffMs()));
        Exception lastException = null;

        for (int i = 1; i <= attempts; i++) {
            try {
                EmbeddingResponse providerResponse = templateAwareEmbeddingExecutor.generateEmbedding(
                        route.providerName(),
                        providerConfig,
                        route.purpose(),
                        route.model(),
                        request.input(),
                        route.inputType(),
                        route.dimension()
                );
                return new EmbeddingResponse(
                        providerResponse.vector(),
                        route.providerName(),
                        route.model(),
                        providerResponse.usage()
                );
            } catch (Exception e) {
                lastException = e;
                boolean retryable = !(e instanceof LlmException llmException) || llmException.isRetryable();
                log.warn("Embedding call failed (attempt {}/{}): provider={}, model={}, retryable={}, errorType={}",
                        i, attempts, route.providerName(), route.model(), retryable, e.getClass().getSimpleName());
                if (i >= attempts || !retryable) {
                    break;
                }
                try {
                    Thread.sleep(backoff.toMillis());
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = backoff.multipliedBy(2);
            }
        }

        throw mapFailure(lastException);
    }

    private BusinessException mapFailure(Exception exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException;
        }
        if (exception instanceof LlmException llmException) {
            String reason = llmException.getReason();
            if ("RATE_LIMIT".equals(reason) || "TIMEOUT".equals(reason) || "SERVER".equals(reason)) {
                return new BusinessException(ErrorCode.LLM_001, "Embedding provider temporarily unavailable", llmException);
            }
            if ("AUTH".equals(reason)) {
                return new BusinessException(ErrorCode.LLM_001, "Embedding request rejected by provider", llmException);
            }
            return new BusinessException(ErrorCode.LLM_001, "Embedding service call failed", llmException);
        }
        return new BusinessException(ErrorCode.LLM_001, "Embedding service call failed", exception);
    }
}
