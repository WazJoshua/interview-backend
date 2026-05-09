package com.josh.interviewj.llm.prompt.service;

import com.josh.interviewj.llm.prompt.dto.PromptTemplateSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates prompt templates for write and runtime operations.
 */
@Service
@Slf4j
public class PromptTemplateValidationService {

    /**
     * Validate runtime variables against template declarations.
     * Returns error message if validation fails, null if passes.
     */
    public String validateRuntimeVariables(
            PromptTemplateSnapshot snapshot,
            Map<String, Object> providedVariables
    ) {
        if (snapshot == null || snapshot.variables() == null) {
            return null;
        }

        List<PromptTemplateSnapshot.VariableDeclaration> declarations = snapshot.variables();

        // Check required variables are provided
        for (PromptTemplateSnapshot.VariableDeclaration decl : declarations) {
            if (decl.required() && !providedVariables.containsKey(decl.name())) {
                return "Required variable not provided: " + decl.name();
            }
        }

        return null;
    }

    /**
     * Validate write-time template content matches variable declarations.
     * Returns error message if validation fails, null if passes.
     */
    public String validateWriteTemplate(
            String systemTemplate,
            String userTemplate,
            String variablesJson
    ) {
        Set<String> declaredNames = parseDeclaredVariableNames(variablesJson);
        Set<String> systemPlaceholders = extractPlaceholders(systemTemplate);
        Set<String> userPlaceholders = extractPlaceholders(userTemplate);

        // Combine all placeholders
        Set<String> allPlaceholders = new HashSet<>();
        allPlaceholders.addAll(systemPlaceholders);
        allPlaceholders.addAll(userPlaceholders);

        // Check that all placeholders are declared
        for (String placeholder : allPlaceholders) {
            if (!declaredNames.contains(placeholder)) {
                return "Placeholder ${" + placeholder + "} not declared in variables";
            }
        }

        // Check for duplicate variable names
        List<PromptTemplateSnapshot.VariableDeclaration> declarations = parseVariableDeclarations(variablesJson);
        Set<String> seenNames = new HashSet<>();
        for (PromptTemplateSnapshot.VariableDeclaration decl : declarations) {
            if (seenNames.contains(decl.name())) {
                return "Duplicate variable name: " + decl.name();
            }
            seenNames.add(decl.name());
        }

        return null;
    }

    private Set<String> extractPlaceholders(String template) {
        if (template == null) {
            return Set.of();
        }

        Set<String> placeholders = new HashSet<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{([A-Za-z0-9_]+)\\}");
        java.util.regex.Matcher matcher = pattern.matcher(template);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        return placeholders;
    }

    private Set<String> parseDeclaredVariableNames(String variablesJson) {
        List<PromptTemplateSnapshot.VariableDeclaration> declarations = parseVariableDeclarations(variablesJson);
        Set<String> names = new HashSet<>();
        for (PromptTemplateSnapshot.VariableDeclaration decl : declarations) {
            names.add(decl.name());
        }
        return names;
    }

    private List<PromptTemplateSnapshot.VariableDeclaration> parseVariableDeclarations(String variablesJson) {
        if (variablesJson == null || variablesJson.isBlank()) {
            return List.of();
        }

        try {
            List<PromptTemplateSnapshot.VariableDeclaration> result = new java.util.ArrayList<>();
            String json = variablesJson.trim();
            if (!json.startsWith("[") || !json.endsWith("]")) {
                return List.of();
            }

            String content = json.substring(1, json.length() - 1);
            if (content.isBlank()) {
                return List.of();
            }

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{([^}]+)\\}");
            java.util.regex.Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String obj = matcher.group(1);
                String name = extractValue(obj, "name");
                boolean required = Boolean.parseBoolean(extractValue(obj, "required"));
                if (name != null) {
                    result.add(new PromptTemplateSnapshot.VariableDeclaration(name, required));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractValue(String objContent, String key) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(objContent);
        if (matcher.find()) {
            return matcher.group(1);
        }
        pattern = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)");
        matcher = pattern.matcher(objContent);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}