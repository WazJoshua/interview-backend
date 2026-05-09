package com.josh.interviewj.llm.template;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.config.LlmProperties;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ClasspathTemplateRegistry implements TemplateRegistry {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;
    private final Map<String, Optional<TemplateDescriptor>> cache = new ConcurrentHashMap<>();

    public ClasspathTemplateRegistry(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.jsonMapper = JsonMapper.builder().build();
        this.yamlMapper = new YAMLMapper();
    }

    @Override
    public Optional<TemplateDescriptor> find(
            String providerName,
            TemplateCapability capability,
            LlmProperties.ProviderProperties providerConfig
    ) {
        String resolvedRoot = resolveRoot(providerName, providerConfig);
        String cacheKey = providerName + "|" + capability.name() + "|" + resolvedRoot;
        return cache.computeIfAbsent(cacheKey, key -> loadDescriptor(providerName, capability, resolvedRoot));
    }

    private Optional<TemplateDescriptor> loadDescriptor(
            String providerName,
            TemplateCapability capability,
            String resolvedRoot
    ) {
        String requestResourcePath = resolvedRoot + "/" + capability.folderName() + "-request.json";
        String responseResourcePath = resolvedRoot + "/" + capability.folderName() + "-response.yml";

        Resource requestResource = resourceLoader.getResource(requestResourcePath);
        Resource responseResource = resourceLoader.getResource(responseResourcePath);
        if (!requestResource.exists() || !responseResource.exists()) {
            return Optional.empty();
        }

        parseJson(requestResource, requestResourcePath);
        parseYaml(responseResource, responseResourcePath);

        return Optional.of(new TemplateDescriptor(providerName, capability, requestResourcePath, responseResourcePath));
    }

    private String resolveRoot(String providerName, LlmProperties.ProviderProperties providerConfig) {
        String configuredRoot = providerConfig.getTemplate() == null ? null : providerConfig.getTemplate().getRoot();
        String resolvedRoot = (configuredRoot == null || configuredRoot.isBlank())
                ? "classpath:/llm-templates/" + providerName
                : configuredRoot;
        if (!resolvedRoot.startsWith("classpath:")) {
            throw new BusinessException("LLM_001", "template.root must start with classpath:");
        }
        return resolvedRoot.endsWith("/") ? resolvedRoot.substring(0, resolvedRoot.length() - 1) : resolvedRoot;
    }

    private JsonNode parseJson(Resource resource, String location) {
        try (InputStream inputStream = resource.getInputStream()) {
            return jsonMapper.readTree(inputStream);
        } catch (Exception exception) {
            throw new BusinessException("LLM_001", "Invalid template JSON: " + location, exception);
        }
    }

    private JsonNode parseYaml(Resource resource, String location) {
        try (InputStream inputStream = resource.getInputStream()) {
            yamlMapper.readValue(inputStream, Object.class);
            return jsonMapper.createObjectNode();
        } catch (Exception exception) {
            throw new BusinessException("LLM_001", "Invalid template YAML: " + location, exception);
        }
    }
}
