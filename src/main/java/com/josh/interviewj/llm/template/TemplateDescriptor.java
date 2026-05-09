package com.josh.interviewj.llm.template;

public record TemplateDescriptor(
        String providerName,
        TemplateCapability capability,
        String requestResourcePath,
        String responseResourcePath
) {
}
