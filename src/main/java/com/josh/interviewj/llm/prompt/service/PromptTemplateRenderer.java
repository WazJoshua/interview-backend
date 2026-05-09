package com.josh.interviewj.llm.prompt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders prompt templates with variable substitution.
 * Uses ${variableName} placeholder syntax.
 */
@Service
@Slf4j
public class PromptTemplateRenderer {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)\\}");

    /**
     * Render a template with variables.
     * Returns null if template is null (indicating system-only or user-only template).
     * Throws IllegalArgumentException if required variable is missing.
     */
    public String render(String template, Map<String, Object> variables) {
        if (template == null) {
            return null;
        }

        String result = template;
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);

        while (matcher.find()) {
            String placeholder = matcher.group(0);
            String varName = matcher.group(1);

            Object value = variables.get(varName);
            if (value == null) {
                // Replace with empty string for optional variables
                log.debug("Variable {} not provided, replacing with empty string", varName);
                result = result.replace(placeholder, "");
            } else {
                result = result.replace(placeholder, String.valueOf(value));
            }
        }

        // Check for remaining unresolved placeholders (indicates error)
        Matcher remainingMatcher = PLACEHOLDER_PATTERN.matcher(result);
        if (remainingMatcher.find()) {
            log.warn("Template still contains unresolved placeholders after rendering: {}", remainingMatcher.group(0));
            throw new IllegalArgumentException("Template contains unresolved placeholder: " + remainingMatcher.group(0));
        }

        return result;
    }

    /**
     * Check if template contains any placeholders.
     */
    public boolean hasPlaceholders(String template) {
        if (template == null) {
            return false;
        }
        return PLACEHOLDER_PATTERN.matcher(template).find();
    }

    /**
     * Extract all placeholder names from a template.
     */
    public java.util.Set<String> extractPlaceholders(String template) {
        if (template == null) {
            return java.util.Set.of();
        }

        java.util.Set<String> placeholders = new java.util.HashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        return placeholders;
    }
}