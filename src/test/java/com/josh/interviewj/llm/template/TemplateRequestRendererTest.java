package com.josh.interviewj.llm.template;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRequestRendererTest {

    private final TemplateRequestRenderer renderer = new TemplateRequestRenderer(JsonMapper.builder().build());

    @Test
    void render_ReplacesVariablesAndPreservesJsonBody() {
        RenderedTemplateRequest rendered = renderer.render(definition("/v1/embeddings"), variables());

        assertThat(rendered.path()).isEqualTo("/v1/embeddings");
        assertThat(rendered.headers().get("Authorization")).isEqualTo("Bearer test-key");
        assertThat(rendered.body()).contains("\"input_type\":\"query\"");
    }

    @Test
    void render_WhenVariableMissing_ThrowsBusinessException() {
        assertThatThrownBy(() -> renderer.render(definition("/v1/embeddings"), new TemplateVariables(
                "https://api.example.com",
                null,
                "Nvidia",
                "kb_query_embedding",
                "text-embedding-v4",
                1000,
                null,
                null,
                "hello",
                "query",
                2
        )))
                .hasMessageContaining("Unbound template variable");
    }

    @Test
    void render_WhenPathDoesNotStartWithSlash_ThrowsBusinessException() {
        assertThatThrownBy(() -> renderer.render(definition("v1/embeddings"), variables()))
                .hasMessageContaining("relative path");
    }

    @Test
    void render_WhenPathStartsWithDoubleSlash_ThrowsBusinessException() {
        assertThatThrownBy(() -> renderer.render(definition("//v1/embeddings"), variables()))
                .hasMessageContaining("must not start with //");
    }

    @Test
    void render_WhenPathContainsScheme_ThrowsBusinessException() {
        assertThatThrownBy(() -> renderer.render(definition("https://api.example.com/v1/embeddings"), variables()))
                .hasMessageContaining("must not contain scheme or host");
    }

    @Test
    void render_WhenPathContainsQuery_ThrowsBusinessException() {
        assertThatThrownBy(() -> renderer.render(definition("/v1/embeddings?bad=true"), variables()))
                .hasMessageContaining("must not contain query or fragment");
    }

    @Test
    void render_WhenPathContainsFragment_ThrowsBusinessException() {
        assertThatThrownBy(() -> renderer.render(definition("/v1/embeddings#frag"), variables()))
                .hasMessageContaining("must not contain query or fragment");
    }

    @Test
    void render_WhenHeaderContainsCrLf_ThrowsBusinessException() {
        TemplateRequestDefinition definition = new TemplateRequestDefinition(
                "POST",
                "/v1/embeddings",
                java.util.Map.of("Authorization", "Bearer ${apiKey}\r\nX-Test: bad"),
                java.util.Map.of(),
                JsonMapper.builder().build().createObjectNode()
        );

        assertThatThrownBy(() -> renderer.render(definition, variables()))
                .hasMessageContaining("CRLF");
    }

    private TemplateRequestDefinition definition(String path) {
        return new TemplateRequestDefinition(
                "POST",
                path,
                java.util.Map.of(
                        "Authorization", "Bearer ${apiKey}"
                ),
                java.util.Map.of(
                        "purpose", "${purpose}"
                ),
                JsonMapper.builder().build().createObjectNode()
                        .put("model", "${model}")
                        .put("input", "${input}")
                        .put("input_type", "${textType}")
        );
    }

    private TemplateVariables variables() {
        return new TemplateVariables(
                "https://api.example.com",
                "test-key",
                "Nvidia",
                "kb_query_embedding",
                "text-embedding-v4",
                1000,
                "system",
                "user",
                "hello",
                "query",
                2
        );
    }
}
