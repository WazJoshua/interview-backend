package com.josh.interviewj.llm;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.llm.core.LlmClient;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.LlmRequest;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.provider.TemplateAwareLlmExecutor;
import com.josh.interviewj.llm.routing.LlmRoute;
import com.josh.interviewj.llm.routing.LlmRouter;
import com.josh.interviewj.llm.support.LlmPurposeCircuitBreaker;
import com.josh.interviewj.llm.support.LlmPurposeCircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Encapsulates structured JSON generation through the configured LLM provider.
 */
@Service
@RequiredArgsConstructor
public class LLMService implements LlmClient {

    private static final String PURPOSE_PARSE = "parse";
    private static final String PURPOSE_ANALYSIS = "analysis";
    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    private final ObjectMapper objectMapper;
    private final LlmRouter llmRouter;
    private final TemplateAwareLlmExecutor templateAwareLlmExecutor;
    private final LlmPurposeCircuitBreakerRegistry breakerRegistry;

    /**
     * Generate a structured JSON output using system/user prompts.
     *
     * <p>This method retries on failures with exponential backoff, and guarantees the returned
     * value is a canonical JSON string.</p>
     *
     * @param systemPrompt system prompt
     * @param userPrompt user prompt
     * @return canonical JSON string
     */
    public String generateStructuredJson(String systemPrompt, String userPrompt) {
        return generateParseStructuredJson(systemPrompt, userPrompt);
    }

    public String generateParseStructuredJson(String systemPrompt, String userPrompt) {
        return generateStructuredJson(new LlmRequest(PURPOSE_PARSE, systemPrompt, userPrompt)).content();
    }

    public String generateAnalysisStructuredJson(String systemPrompt, String userPrompt) {
        return generateStructuredJson(new LlmRequest(PURPOSE_ANALYSIS, systemPrompt, userPrompt)).content();
    }

    @Override
    public LlmResponse generateStructuredJson(LlmRequest request) {
        return generateStructuredJson(request, null);
    }

    public LlmResponse generateStructuredJson(LlmRequest request, Consumer<String> schemaValidator) {
        if (request == null || request.purpose() == null || request.purpose().isBlank()) {
            throw new BusinessException("LLM_001", "LLM request purpose is required");
        }

        LlmRoute route = llmRouter.resolve(request.purpose());
        if (route.providerConfig().getApiKey() == null || route.providerConfig().getApiKey().isBlank()) {
            throw new BusinessException("LLM_001", "LLM api key is not configured for provider: " + route.providerName());
        }

        // Check circuit breaker before attempting the call
        LlmPurposeCircuitBreaker breaker = breakerRegistry.getBreaker(request.purpose());
        if (!breaker.allow()) {
            throw new BusinessException("LLM_002",
                    "LLM service circuit breaker is open for purpose: " + request.purpose());
        }

        int attempts = Math.max(1, route.providerConfig().getMaxRetries());
        Duration backoff = Duration.ofMillis(Math.max(1, route.providerConfig().getRetryBackoffMs()));
        Exception lastException = null;

        for (int i = 1; i <= attempts; i++) {
            try {
                LlmResponse providerResponse = templateAwareLlmExecutor.generateText(
                        route.providerName(),
                        route.providerConfig(),
                        route.purpose(),
                        route.model(),
                        request.systemPrompt(),
                        request.userPrompt()
                );

                String json = extractJsonPayloadFromResponse(providerResponse.content());

                JsonNode node = objectMapper.readTree(json);
                LlmResponse response = new LlmResponse(
                        objectMapper.writeValueAsString(node),
                        route.providerName(),
                        route.model(),
                        providerResponse.usage()
                );

                if (schemaValidator != null) {
                    schemaValidator.accept(response.content());
                }
                breaker.recordSuccess();

                return response;
            } catch (Exception e) {
                lastException = e;
                String reason = resolveFailureReason(e);
                boolean retryable = !(e instanceof LlmException llmException) || llmException.isRetryable();
                log.warn("LLM call failed (attempt {}/{}): provider={}, model={}, retryable={}, reason={}, errorType={}, message={}",
                        i,
                        attempts,
                        route.providerName(),
                        route.model(),
                        retryable,
                        reason,
                        e.getClass().getSimpleName(),
                        abbreviate(e.getMessage()));
                if (i >= attempts || !retryable) {
                    break;
                }
                try {
                    Thread.sleep(backoff.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = backoff.multipliedBy(2);
            }
        }

        // Record failure after all attempts exhausted
        breaker.recordFailure();

        throw new BusinessException("LLM_001", buildFailureMessage(lastException), lastException);
    }

    /**
     * Extract JSON string from the raw provider response.
     *
     * @param responseBody raw response body
     * @return extracted JSON string
     */
    private String extractJsonPayloadFromResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new BusinessException("LLM_001", "Empty response from LLM service");
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.isObject() || root.isArray()) {
                return objectMapper.writeValueAsString(root);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ignore) {
            // fall through
        }

        return extractJsonFromText(responseBody);
    }

    /**
     * Extract JSON from plain text.
     *
     * <p>Handles common formats such as markdown code fences and provider wrappers.</p>
     *
     * @param text raw text
     * @return JSON string
     */
    private String extractJsonFromText(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException("LLM_001", "LLM returned empty content");
        }

        int fenceStart = trimmed.indexOf("```json");
        if (fenceStart >= 0) {
            int start = trimmed.indexOf('\n', fenceStart);
            int end = trimmed.indexOf("```", start >= 0 ? start : fenceStart + 7);
            if (start >= 0 && end > start) {
                return trimmed.substring(start + 1, end).trim();
            }
        }

        int fenceStartPlain = trimmed.indexOf("```");
        if (fenceStartPlain >= 0) {
            int start = trimmed.indexOf('\n', fenceStartPlain);
            int end = trimmed.indexOf("```", start >= 0 ? start : fenceStartPlain + 3);
            if (start >= 0 && end > start) {
                String inside = trimmed.substring(start + 1, end).trim();
                if (inside.startsWith("{") || inside.startsWith("[")) {
                    return inside;
                }
            }
        }

        int objStart = trimmed.indexOf('{');
        int objEnd = trimmed.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return trimmed.substring(objStart, objEnd + 1).trim();
        }
        int arrStart = trimmed.indexOf('[');
        int arrEnd = trimmed.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) {
            return trimmed.substring(arrStart, arrEnd + 1).trim();
        }

        throw new BusinessException("LLM_001", "LLM output does not contain JSON");
    }

    private String buildFailureMessage(Exception exception) {
        if (exception == null) {
            return "LLM service call failed";
        }

        String reason = resolveFailureReason(exception);
        String message = abbreviate(exception.getMessage());
        if (message == null || message.isBlank()) {
            return "LLM service call failed: " + reason;
        }
        return "LLM service call failed: " + reason + " - " + message;
    }

    private String resolveFailureReason(Exception exception) {
        if (exception instanceof LlmException llmException) {
            return llmException.getReason();
        }
        return exception.getClass().getSimpleName();
    }

    private String abbreviate(String message) {
        if (message == null) {
            return null;
        }

        String normalized = message.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 237) + "...";
    }
}
