package com.josh.interviewj.llm.provider;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.EmbeddingResponse;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.josh.interviewj.usage.model.UsageFamily;
import com.openai.models.embeddings.EmbeddingCreateParams;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EmbeddingHttpClient implements EmbeddingProviderClient {

    private static final String PROVIDER = "default";

    private final OpenAiClientFactory openAiClientFactory;

    /**
     * Creates the provider client with a centralized SDK factory.
     *
     * @param openAiClientFactory factory for SDK clients
     */
    public EmbeddingHttpClient(OpenAiClientFactory openAiClientFactory) {
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
     * Generates an embedding vector through the OpenAI-compatible embeddings API.
     *
     * @param providerConfig provider configuration
     * @param model model name
     * @param input input text
     * @param textType logical text type, preserved for interface compatibility
     * @param dimensions expected dimensions
     * @return embedding vector
     */
    @Override
    public EmbeddingResponse generateEmbedding(
            LlmProperties.ProviderProperties providerConfig,
            String model,
            String input,
            String textType,
            int dimensions
    ) {
        try {
            OpenAIClient client = openAiClientFactory.create(providerConfig);
            CreateEmbeddingResponse response = client.embeddings().create(buildRequest(model, input, dimensions));
            return new EmbeddingResponse(
                    extractEmbedding(response, dimensions),
                    PROVIDER,
                    model,
                    extractUsage(response)
            );
        } catch (OpenAIServiceException e) {
            throw mapServiceError(e);
        } catch (OpenAIIoException e) {
            throw new LlmException("Embedding provider call timed out", "TIMEOUT", true, e);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Embedding provider call failed", "UNKNOWN", false, e);
        }
    }

    /**
     * Builds the OpenAI-compatible embeddings request.
     *
     * @param model model name
     * @param input input text
     * @param dimensions target dimensions
     * @return request payload
     */
    private EmbeddingCreateParams buildRequest(String model, String input, int dimensions) {
        return EmbeddingCreateParams.builder()
                .model(model)
                .input(input)
                .dimensions(dimensions)
                .build();
    }

    /**
     * Extracts the first embedding vector from the SDK response.
     *
     * @param response embedding response
     * @param dimensions expected dimensions
     * @return embedding vector
     */
    private float[] extractEmbedding(CreateEmbeddingResponse response, int dimensions) {
        if (response.data().isEmpty()) {
            throw new LlmException("Embedding response is invalid", "INVALID_RESPONSE", false);
        }

        Embedding embedding = response.data().getFirst();
        List<Float> values = embedding.embedding();
        if (values == null || values.isEmpty()) {
            throw new LlmException("Embedding vector is missing", "INVALID_RESPONSE", false);
        }

        if (values.size() != dimensions) {
            throw new LlmException("Embedding dimension mismatch", "INVALID_RESPONSE", false);
        }

        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private ProviderUsage extractUsage(CreateEmbeddingResponse response) {
        try {
            CreateEmbeddingResponse.Usage usage = response.usage();
            return new ProviderUsage(
                    UsageFamily.EMBEDDING,
                    1L,
                    usage.promptTokens(),
                    null,
                    usage.totalTokens(),
                    null
            );
        } catch (Exception exception) {
            return null;
        }
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
            return new LlmException("Embedding provider authentication failed: " + details, "AUTH", false, exception);
        }
        if (exception instanceof RateLimitException || statusCode == 429) {
            return new LlmException("Embedding provider rate limited: " + details, "RATE_LIMIT", true, exception);
        }
        if (statusCode >= 500) {
            return new LlmException("Embedding provider server error: " + details, "SERVER", true, exception);
        }
        return new LlmException("Embedding provider request failed: " + details, "HTTP_" + statusCode, false, exception);
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
