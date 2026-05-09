package com.josh.interviewj.llm.prompt.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateRendererTest {

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    void render_NullTemplate_ReturnsNull() {
        String result = renderer.render(null, Map.of("name", "value"));
        assertThat(result).isNull();
    }

    @Test
    void render_NoPlaceholders_ReturnsOriginal() {
        String result = renderer.render("Hello World", Map.of());
        assertThat(result).isEqualTo("Hello World");
    }

    @Test
    void render_SinglePlaceholder_ReplacesCorrectly() {
        String result = renderer.render("Hello ${name}", Map.of("name", "World"));
        assertThat(result).isEqualTo("Hello World");
    }

    @Test
    void render_MultiplePlaceholders_ReplacesAll() {
        String result = renderer.render("${greeting} ${name}!", Map.of("greeting", "Hello", "name", "World"));
        assertThat(result).isEqualTo("Hello World!");
    }

    @Test
    void render_MissingVariable_ReplacesEmpty() {
        String result = renderer.render("Hello ${name}", Map.of());
        assertThat(result).isEqualTo("Hello ");
    }

    @Test
    void render_RemainingPlaceholder_ThrowsException() {
        // After replacement, if placeholder remains, it means the value had a placeholder pattern
        assertThatThrownBy(() -> renderer.render("${name}", Map.of("name", "${another}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved placeholder");
    }

    @Test
    void hasPlaceholders_TrueWhenPresent() {
        assertThat(renderer.hasPlaceholders("Hello ${name}")).isTrue();
    }

    @Test
    void hasPlaceholders_FalseWhenAbsent() {
        assertThat(renderer.hasPlaceholders("Hello World")).isFalse();
    }

    @Test
    void extractPlaceholders_ReturnsAllNames() {
        assertThat(renderer.extractPlaceholders("${greeting} ${name}!"))
                .containsExactlyInAnyOrder("greeting", "name");
    }
}