package com.josh.interviewj.llm.template;

import java.util.List;
import java.util.Map;

public record TemplateHttpResponse(
        int statusCode,
        Map<String, List<String>> headers,
        String contentType,
        String body
) {
}
