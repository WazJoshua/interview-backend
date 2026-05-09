package com.josh.interviewj.llm.template;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.josh.interviewj.usage.model.UsageFamily;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TemplateResponseExtractor {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final YAMLMapper yamlMapper;
    private final JsonPathValueReader pathValueReader = new JsonPathValueReader();
    private final Map<String, TemplateResponseDefinition> responseCache = new ConcurrentHashMap<>();

    @Autowired
    public TemplateResponseExtractor(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.yamlMapper = new YAMLMapper();
    }

    public TemplateResponseExtractor(ObjectMapper objectMapper) {
        this(new DefaultResourceLoader(), objectMapper);
    }

    public String extractChatContent(TemplateDescriptor descriptor, TemplateHttpResponse response) {
        return extractChatContent(loadDefinition(descriptor.responseResourcePath()), response);
    }

    public String extractChatContent(TemplateResponseDefinition definition, TemplateHttpResponse response) {
        JsonNode root = parseJson(response);
        if (response.statusCode() >= 400) {
            throw mapErrorResponse(definition, response.statusCode(), root);
        }

        JsonNode contentNode = pathValueReader.read(root, definition.contentPath());
        if (contentNode == null || !contentNode.isTextual() || contentNode.asText().isBlank()) {
            throw new LlmException("Template chat response content is invalid", "INVALID_RESPONSE", false);
        }
        return contentNode.asText();
    }

    public float[] extractEmbedding(TemplateDescriptor descriptor, TemplateHttpResponse response, int dimensions) {
        return extractEmbedding(loadDefinition(descriptor.responseResourcePath()), response, dimensions);
    }

    public float[] extractEmbedding(TemplateResponseDefinition definition, TemplateHttpResponse response, int dimensions) {
        JsonNode root = parseJson(response);
        if (response.statusCode() >= 400) {
            throw mapErrorResponse(definition, response.statusCode(), root);
        }

        JsonNode vectorNode = pathValueReader.read(root, definition.vectorPath());
        if (vectorNode == null || !vectorNode.isArray() || vectorNode.isEmpty()) {
            throw new LlmException("Template embedding response vector is invalid", "INVALID_RESPONSE", false);
        }

        if (vectorNode.size() != dimensions) {
            throw new LlmException("Template embedding response dimension mismatch", "INVALID_RESPONSE", false);
        }

        float[] vector = new float[vectorNode.size()];
        for (int i = 0; i < vectorNode.size(); i++) {
            JsonNode value = vectorNode.get(i);
            if (!value.isNumber()) {
                throw new LlmException("Template embedding response vector must contain numbers", "INVALID_RESPONSE", false);
            }
            vector[i] = value.floatValue();
        }
        return vector;
    }

    public ProviderUsage extractUsage(
            TemplateDescriptor descriptor,
            TemplateHttpResponse response,
            UsageFamily usageFamily,
            Long defaultRequestCount
    ) {
        return extractUsage(loadDefinition(descriptor.responseResourcePath()), response, usageFamily, defaultRequestCount);
    }

    public ProviderUsage extractUsage(
            TemplateResponseDefinition definition,
            TemplateHttpResponse response,
            UsageFamily usageFamily,
            Long defaultRequestCount
    ) {
        JsonNode root = parseJson(response);
        if (response.statusCode() >= 400) {
            throw mapErrorResponse(definition, response.statusCode(), root);
        }
        if (!hasUsageDefinition(definition)) {
            return null;
        }

        return new ProviderUsage(
                usageFamily,
                readLongValue(root, definition.requestCountPath(), defaultRequestCount),
                readLongValue(root, definition.promptTokensPath(), null),
                readLongValue(root, definition.completionTokensPath(), null),
                readLongValue(root, definition.totalTokensPath(), null),
                readLongValue(root, definition.cachedTokensPath(), null)
        );
    }

    private TemplateResponseDefinition loadDefinition(String resourcePath) {
        return responseCache.computeIfAbsent(resourcePath, path -> {
            try (var inputStream = resourceLoader.getResource(path).getInputStream()) {
                return yamlMapper.readValue(inputStream, TemplateResponseDefinition.class);
            } catch (Exception exception) {
                throw new BusinessException("LLM_001", "Template response definition is invalid: " + path, exception);
            }
        });
    }

    private JsonNode parseJson(TemplateHttpResponse response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception exception) {
            throw new LlmException("Template response body must be valid JSON", "INVALID_RESPONSE", false, exception);
        }
    }

    private LlmException mapErrorResponse(TemplateResponseDefinition definition, int statusCode, JsonNode root) {
        String message = "status=" + statusCode;
        if (definition.errorMessagePath() != null && !definition.errorMessagePath().isBlank()) {
            JsonNode messageNode = pathValueReader.read(root, definition.errorMessagePath());
            if (messageNode != null && messageNode.isValueNode()) {
                message = messageNode.asText();
            }
        }

        if (statusCode == 401 || statusCode == 403) {
            return new LlmException("Template provider authentication failed: " + message, "AUTH", false);
        }
        if (statusCode == 429) {
            return new LlmException("Template provider rate limited: " + message, "RATE_LIMIT", true);
        }
        if (statusCode >= 500) {
            return new LlmException("Template provider server error: " + message, "SERVER", true);
        }
        return new LlmException("Template provider request failed: " + message, "HTTP_" + statusCode, false);
    }

    private boolean hasUsageDefinition(TemplateResponseDefinition definition) {
        return hasText(definition.promptTokensPath())
                || hasText(definition.completionTokensPath())
                || hasText(definition.totalTokensPath())
                || hasText(definition.cachedTokensPath())
                || hasText(definition.requestCountPath());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Long readLongValue(JsonNode root, String path, Long defaultValue) {
        if (!hasText(path)) {
            return defaultValue;
        }
        JsonNode node = pathValueReader.read(root, path);
        if (node == null || !node.isNumber()) {
            return defaultValue;
        }
        return node.longValue();
    }
}
