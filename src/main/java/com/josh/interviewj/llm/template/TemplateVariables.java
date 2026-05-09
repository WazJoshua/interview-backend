package com.josh.interviewj.llm.template;

import java.util.LinkedHashMap;
import java.util.Map;

public record TemplateVariables(
        String baseUrl,
        String apiKey,
        String providerName,
        String purpose,
        String model,
        Integer timeoutMs,
        String systemPrompt,
        String userPrompt,
        String input,
        String textType,
        Integer dimensions
) {

    public Map<String, Object> asMap() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("baseUrl", baseUrl);
        variables.put("apiKey", apiKey);
        variables.put("providerName", providerName);
        variables.put("purpose", purpose);
        variables.put("model", model);
        variables.put("timeoutMs", timeoutMs);
        variables.put("systemPrompt", systemPrompt);
        variables.put("userPrompt", userPrompt);
        variables.put("input", input);
        variables.put("textType", textType);
        variables.put("dimensions", dimensions);
        return variables;
    }
}
