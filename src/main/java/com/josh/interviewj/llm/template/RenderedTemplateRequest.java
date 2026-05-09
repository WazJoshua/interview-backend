package com.josh.interviewj.llm.template;

import java.util.Map;

public record RenderedTemplateRequest(
        String method,
        String path,
        Map<String, String> headers,
        Map<String, String> query,
        String body
) {
}
