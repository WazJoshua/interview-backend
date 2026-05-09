package com.josh.interviewj.llm.template;

import com.josh.interviewj.llm.core.LlmException;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateResponseExtractorTest {

    private final TemplateResponseExtractor extractor = new TemplateResponseExtractor(JsonMapper.builder().build());

    @Test
    void extractChatContent_WhenResponseIsValid_ReturnsContent() {
        String content = extractor.extractChatContent(
                new TemplateResponseDefinition("chat", "choices[0].message.content", null, "error.message"),
                response(200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}")
        );

        assertThat(content).isEqualTo("{\"ok\":true}");
    }

    @Test
    void extractEmbedding_WhenResponseIsValid_ReturnsVector() {
        float[] embedding = extractor.extractEmbedding(
                new TemplateResponseDefinition("embedding", null, "data[0].embedding", "error.message"),
                response(200, "{\"data\":[{\"embedding\":[0.1,0.2]}]}"),
                2
        );

        assertThat(embedding).containsExactly(0.1f, 0.2f);
    }

    @Test
    void extractUsage_WhenDefinitionContainsUsagePaths_ReturnsUsage() {
        ProviderUsage usage = extractor.extractUsage(
                new TemplateResponseDefinition(
                        "embedding",
                        null,
                        "data[0].embedding",
                        "error.message",
                        "usage.prompt_tokens",
                        null,
                        "usage.total_tokens",
                        null,
                        null
                ),
                response(200, "{\"usage\":{\"prompt_tokens\":4,\"total_tokens\":6}}"),
                UsageFamily.EMBEDDING,
                1L
        );

        assertThat(usage.usageFamily()).isEqualTo(UsageFamily.EMBEDDING);
        assertThat(usage.requestCount()).isEqualTo(1L);
        assertThat(usage.promptTokens()).isEqualTo(4L);
        assertThat(usage.totalTokens()).isEqualTo(6L);
    }

    @Test
    void extractChatContent_WhenBodyIsNotJson_ThrowsInvalidResponse() {
        assertThatThrownBy(() -> extractor.extractChatContent(
                new TemplateResponseDefinition("chat", "choices[0].message.content", null, "error.message"),
                response(200, "not-json")
        ))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("valid JSON");
    }

    @Test
    void extractEmbedding_WhenDimensionMismatches_ThrowsInvalidResponse() {
        assertThatThrownBy(() -> extractor.extractEmbedding(
                new TemplateResponseDefinition("embedding", null, "data[0].embedding", "error.message"),
                response(200, "{\"data\":[{\"embedding\":[0.1]}]}"),
                2
        ))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("dimension");
    }

    private TemplateHttpResponse response(int statusCode, String body) {
        return new TemplateHttpResponse(statusCode, Map.of(), "application/json", body);
    }
}
