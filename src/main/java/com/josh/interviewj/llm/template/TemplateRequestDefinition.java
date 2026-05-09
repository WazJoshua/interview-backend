package com.josh.interviewj.llm.template;

import tools.jackson.databind.JsonNode;

import java.util.Map;

public record TemplateRequestDefinition(
        String method,
        String path,
        Map<String, String> headers,
        Map<String, String> query,
        JsonNode body
) {
}
