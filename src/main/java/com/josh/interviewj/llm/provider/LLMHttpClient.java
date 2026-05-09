package com.josh.interviewj.llm.provider;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.josh.interviewj.usage.model.UsageFamily;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;

@Component
public class LLMHttpClient implements LlmProviderClient {

    private static final String PROVIDER = "default";

    private final OpenAiClientFactory openAiClientFactory;

    /**
     * Creates the provider client with a centralized SDK factory.
     *
     * @param openAiClientFactory factory for SDK clients
     */
    public LLMHttpClient(OpenAiClientFactory openAiClientFactory) {
        this.openAiClientFactory = openAiClientFactory;
    }

    /**
     * Returns the provider key supported by this adapter.
     *
     * @return provider key
     */
    @Override
    public String provider() {
        return PROVIDER;
    }

    /**
     * Generates assistant text with the OpenAI-compatible chat completions API.
     *
     * @param providerConfig provider configuration
     * @param model model name
     * @param systemPrompt system prompt
     * @param userPrompt user prompt
     * @return assistant text content
     */
    @Override
    public LlmResponse generateText(
            LlmProperties.ProviderProperties providerConfig,
            String model,
            String systemPrompt,
            String userPrompt
    ) {
        try {
            OpenAIClient client = openAiClientFactory.create(providerConfig);
            ChatCompletion completion = client.chat()
                    .completions()
                    .create(buildRequest(model, systemPrompt, userPrompt));
            return new LlmResponse(
                    extractTextContent(completion),
                    PROVIDER,
                    model,
                    extractUsage(completion)
            );
        } catch (OpenAIServiceException e) {
            throw mapServiceError(e);
        } catch (OpenAIIoException e) {
            throw mapIoError(providerConfig, e);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM provider call failed", "UNKNOWN", false, e);
        }
    }

    /**
     * Builds the OpenAI-compatible chat completion request.
     *
     * @param model model name
     * @param systemPrompt system prompt
     * @param userPrompt user prompt
     * @return request payload
     */
    private ChatCompletionCreateParams buildRequest(String model, String systemPrompt, String userPrompt) {
        return ChatCompletionCreateParams.builder()
                .model(model)
                .addSystemMessage(systemPrompt)
                .addUserMessage(userPrompt)
                .temperature(0.2)
                .responseFormat(ResponseFormatJsonObject.builder()
                        .type(JsonValue.from("json_object"))
                        .build())
                .build();
    }

    /**
     * Extracts the final assistant text from the SDK response.
     *
     * @param completion chat completion response
     * @return assistant message content
     */
    private String extractTextContent(ChatCompletion completion) {
        if (completion.choices().isEmpty()) {
            throw new LlmException("Empty response from LLM provider", "EMPTY_RESPONSE", true);
        }

        String content = completion.choices().getFirst().message().content().orElse(null);
        if (content == null || content.isBlank()) {
            throw new LlmException("LLM provider returned empty content", "EMPTY_RESPONSE", true);
        }
        return content;
    }

    private ProviderUsage extractUsage(ChatCompletion completion) {
        return completion.usage()
                .map(usage -> new ProviderUsage(
                        UsageFamily.CHAT,
                        1L,
                        usage.promptTokens(),
                        usage.completionTokens(),
                        usage.totalTokens(),
                        extractCachedTokens(usage)
                ))
                .orElse(null);
    }

    private Long extractCachedTokens(CompletionUsage usage) {
        return usage.promptTokensDetails()
                .flatMap(CompletionUsage.PromptTokensDetails::cachedTokens)
                .orElse(null);
    }

    /**
     * Maps SDK service exceptions to provider-neutral reasons.
     *
     * @param exception SDK service exception
     * @return mapped LLM exception
     */
    private LlmException mapServiceError(OpenAIServiceException exception) {
        int statusCode = exception.statusCode();
        String details = buildServiceErrorDetails(exception);
        if (exception instanceof UnauthorizedException || exception instanceof PermissionDeniedException
                || statusCode == 401 || statusCode == 403) {
            return new LlmException("LLM provider authentication failed: " + details, "AUTH", false, exception);
        }
        if (exception instanceof RateLimitException || statusCode == 429) {
            return new LlmException("LLM provider rate limited: " + details, "RATE_LIMIT", true, exception);
        }
        if (statusCode >= 500) {
            return new LlmException("LLM provider server error: " + details, "SERVER", true, exception);
        }
        return new LlmException("LLM provider request failed: " + details, "HTTP_" + statusCode, false, exception);
    }

    /**
     * Maps SDK I/O failures into timeout or generic network exceptions with actionable diagnostics.
     *
     * @param providerConfig provider configuration used for the request
     * @param exception      SDK I/O exception
     * @return mapped LLM exception
     */
    private LlmException mapIoError(LlmProperties.ProviderProperties providerConfig, OpenAIIoException exception) {
        String details = buildIoErrorDetails(providerConfig, exception);
        if (isTimeoutRelated(exception)) {
            return new LlmException("LLM provider call timed out: " + details, "TIMEOUT", true, exception);
        }
        return new LlmException("LLM provider I/O failed: " + details, "IO", true, exception);
    }

    /**
     * Builds a concise detail message for SDK I/O failures.
     *
     * @param providerConfig provider configuration used for the request
     * @param exception      SDK I/O exception
     * @return normalized detail string
     */
    private String buildIoErrorDetails(LlmProperties.ProviderProperties providerConfig, OpenAIIoException exception) {
        Throwable rootCause = findRootCause(exception);
        String rootType = rootCause == null ? exception.getClass().getSimpleName() : rootCause.getClass().getSimpleName();
        String rootMessage = summarize(rootCause == null ? exception.getMessage() : rootCause.getMessage());
        String exceptionMessage = summarize(exception.getMessage());
        StringBuilder builder = new StringBuilder("timeoutMs=").append(providerConfig.getTimeoutMs())
                .append(", cause=").append(rootType);
        if (rootMessage != null && !rootMessage.isBlank()) {
            builder.append(", ").append(rootMessage);
        }
        if (exceptionMessage != null && !exceptionMessage.isBlank()
                && (rootMessage == null || !exceptionMessage.equals(rootMessage))) {
            builder.append(", sdkMessage=").append(exceptionMessage.substring("body=".length()));
        }
        return builder.toString();
    }

    /**
     * Builds a concise error summary from the SDK exception.
     *
     * @param exception SDK service exception
     * @return normalized detail string
     */
    private String buildServiceErrorDetails(OpenAIServiceException exception) {
        String body = exception.body() == null ? null : exception.body().toString();
        String message = exception.getMessage();
        String summary = summarize(body != null && !body.isBlank() ? body : message);
        if (summary == null || summary.isBlank()) {
            return "status=" + exception.statusCode();
        }
        return "status=" + exception.statusCode() + ", " + summary;
    }

    /**
     * Normalizes arbitrary detail text for exception messages.
     *
     * @param value response body or SDK message
     * @return abbreviated detail
     */
    private String summarize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "body=" + abbreviate(value);
    }

    /**
     * Determines whether the SDK I/O exception is timeout-related.
     *
     * @param exception SDK I/O exception
     * @return {@code true} when the root cause indicates a timeout
     */
    private boolean isTimeoutRelated(OpenAIIoException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof HttpTimeoutException || current instanceof InterruptedIOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("timeout") || normalized.contains("timed out")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Returns the deepest cause in the throwable chain.
     *
     * @param throwable source throwable
     * @return deepest cause or the original throwable when no cause exists
     */
    private Throwable findRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? throwable : current;
    }

    /**
     * Abbreviates long error content for logs and exceptions.
     *
     * @param value original value
     * @return abbreviated value
     */
    private String abbreviate(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 237) + "...";
    }
}
