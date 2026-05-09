package com.josh.interviewj.llm.template;

import com.josh.interviewj.config.LlmProperties;

import java.util.Optional;

public interface TemplateRegistry {

    Optional<TemplateDescriptor> find(
            String providerName,
            TemplateCapability capability,
            LlmProperties.ProviderProperties providerConfig
    );
}
