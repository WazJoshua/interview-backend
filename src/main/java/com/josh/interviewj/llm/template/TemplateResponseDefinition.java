package com.josh.interviewj.llm.template;

public record TemplateResponseDefinition(
        String type,
        String contentPath,
        String vectorPath,
        String errorMessagePath,
        String promptTokensPath,
        String completionTokensPath,
        String totalTokensPath,
        String cachedTokensPath,
        String requestCountPath
) {

    public TemplateResponseDefinition(String type, String contentPath, String vectorPath, String errorMessagePath) {
        this(type, contentPath, vectorPath, errorMessagePath, null, null, null, null, null);
    }
}
