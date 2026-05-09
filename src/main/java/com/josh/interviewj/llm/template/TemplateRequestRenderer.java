package com.josh.interviewj.llm.template;

import com.josh.interviewj.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TemplateRequestRenderer {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9]+)}");

    private final ObjectMapper objectMapper;

    public TemplateRequestRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RenderedTemplateRequest render(TemplateRequestDefinition definition, TemplateVariables variables) {
        Map<String, Object> valueMap = variables.asMap();
        String path = renderPath(definition.path(), valueMap);
        Map<String, String> headers = renderHeaders(definition.headers(), valueMap);
        Map<String, String> query = renderQuery(definition.query(), valueMap);
        String body = renderBody(definition.body(), valueMap);

        return new RenderedTemplateRequest(
                definition.method(),
                path,
                headers,
                query,
                body
        );
    }

    private String renderPath(String template, Map<String, Object> variables) {
        String path = replaceSubstrings(template, variables);
        String lower = path.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            throw new BusinessException("LLM_001", "Template request path must not contain scheme or host");
        }
        if (path == null || !path.startsWith("/")) {
            throw new BusinessException("LLM_001", "Template request path must be a relative path starting with /");
        }
        if (path.startsWith("//")) {
            throw new BusinessException("LLM_001", "Template request path must not start with //");
        }
        if (path.contains("?") || path.contains("#")) {
            throw new BusinessException("LLM_001", "Template request path must not contain query or fragment");
        }
        return path;
    }

    private Map<String, String> renderHeaders(Map<String, String> headers, Map<String, Object> variables) {
        Map<String, String> rendered = new LinkedHashMap<>();
        if (headers == null) {
            return rendered;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String value = replaceSubstrings(entry.getValue(), variables);
            if (value.contains("\r") || value.contains("\n")) {
                throw new BusinessException("LLM_001", "Template header must not contain CRLF characters");
            }
            rendered.put(entry.getKey(), value);
        }
        return rendered;
    }

    private Map<String, String> renderQuery(Map<String, String> query, Map<String, Object> variables) {
        Map<String, String> rendered = new LinkedHashMap<>();
        if (query == null) {
            return rendered;
        }
        for (Map.Entry<String, String> entry : query.entrySet()) {
            rendered.put(entry.getKey(), replaceSubstrings(entry.getValue(), variables));
        }
        return rendered;
    }

    private String renderBody(JsonNode body, Map<String, Object> variables) {
        if (body == null || body.isNull()) {
            return "{}";
        }
        JsonNode rendered = renderBodyNode(body.deepCopy(), variables);
        try {
            return objectMapper.writeValueAsString(rendered);
        } catch (Exception exception) {
            throw new BusinessException("LLM_001", "Failed to render template body", exception);
        }
    }

    private JsonNode renderBodyNode(JsonNode node, Map<String, Object> variables) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            for (Map.Entry<String, JsonNode> entry : objectNode.properties()) {
                objectNode.set(entry.getKey(), renderBodyNode(entry.getValue(), variables));
            }
            return objectNode;
        }
        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                arrayNode.set(i, renderBodyNode(arrayNode.get(i), variables));
            }
            return arrayNode;
        }
        if (node.isTextual()) {
            String value = node.textValue();
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
            if (matcher.matches()) {
                Object resolved = variables.get(matcher.group(1));
                if (resolved == null) {
                    throw new BusinessException("LLM_001", "Unbound template variable: " + matcher.group(1));
                }
                return objectMapper.valueToTree(resolved);
            }
            if (value.contains("${")) {
                throw new BusinessException("LLM_001", "Unbound template variable in template body: " + value);
            }
        }
        return node;
    }

    private String replaceSubstrings(String template, Map<String, Object> variables) {
        if (template == null) {
            return null;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = variables.get(key);
            if (value == null) {
                throw new BusinessException("LLM_001", "Unbound template variable: " + key);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        if (result.toString().contains("${")) {
            throw new BusinessException("LLM_001", "Unbound template variable remains after rendering");
        }
        return result.toString();
    }
}
