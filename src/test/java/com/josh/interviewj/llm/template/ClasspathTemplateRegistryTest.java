package com.josh.interviewj.llm.template;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.config.LlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClasspathTemplateRegistryTest {

    @Test
    void find_WhenTemplateExists_ReturnsDescriptorAndCachesLookup() {
        CountingResourceLoader resourceLoader = new CountingResourceLoader();
        ClasspathTemplateRegistry registry = new ClasspathTemplateRegistry(resourceLoader);
        LlmProperties.ProviderProperties providerConfig = provider("classpath:/llm-templates/dispatcher_rc", true);

        Optional<TemplateDescriptor> first = registry.find("dispatcher_rc", TemplateCapability.CHAT, providerConfig);
        Optional<TemplateDescriptor> second = registry.find("dispatcher_rc", TemplateCapability.CHAT, providerConfig);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().requestResourcePath()).isEqualTo("classpath:/llm-templates/dispatcher_rc/chat-request.json");
        assertThat(resourceLoader.getAccessCount("classpath:/llm-templates/dispatcher_rc/chat-request.json")).isEqualTo(1);
    }

    @Test
    void find_WhenTemplateMissing_ReturnsEmptyEvenWhenStrict() {
        ClasspathTemplateRegistry registry = new ClasspathTemplateRegistry(new DefaultResourceLoader());
        LlmProperties.ProviderProperties providerConfig = provider("classpath:/llm-templates/missing", true);

        assertThat(registry.find("missing", TemplateCapability.CHAT, providerConfig)).isEmpty();
    }

    @Test
    void find_WhenRootIsInvalid_ThrowsBusinessException() {
        ClasspathTemplateRegistry registry = new ClasspathTemplateRegistry(new DefaultResourceLoader());
        LlmProperties.ProviderProperties providerConfig = provider("https://provider.example.com/templates", false);

        assertThatThrownBy(() -> registry.find("bad_root", TemplateCapability.CHAT, providerConfig))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("classpath:");
    }

    private LlmProperties.ProviderProperties provider(String root, boolean strict) {
        LlmProperties.ProviderProperties provider = new LlmProperties.ProviderProperties();
        provider.setBaseUrl("https://provider.example.com");
        provider.setApiKey("test-key");
        LlmProperties.TemplateProperties template = new LlmProperties.TemplateProperties();
        template.setEnabled(true);
        template.setStrict(strict);
        template.setRoot(root);
        provider.setTemplate(template);
        return provider;
    }

    private static class CountingResourceLoader implements ResourceLoader {
        private final DefaultResourceLoader delegate = new DefaultResourceLoader();
        private final Map<String, Integer> accessCounts = new ConcurrentHashMap<>();

        @Override
        public Resource getResource(String location) {
            accessCounts.merge(location, 1, Integer::sum);
            return delegate.getResource(location);
        }

        @Override
        public ClassLoader getClassLoader() {
            return delegate.getClassLoader();
        }

        int getAccessCount(String location) {
            return accessCounts.getOrDefault(location, 0);
        }
    }
}
