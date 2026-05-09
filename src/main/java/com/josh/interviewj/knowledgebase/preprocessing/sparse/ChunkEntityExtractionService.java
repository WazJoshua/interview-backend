package com.josh.interviewj.knowledgebase.preprocessing.sparse;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChunkEntityExtractionService {

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\\b[A-Z]{2,}_[0-9]{3,}\\b");
    private static final Pattern CONFIG_KEY_PATTERN = Pattern.compile("\\b[a-z][a-z0-9]*(?:[._-][a-z0-9]+){1,}\\b");
    private static final Pattern ENV_ASSIGNMENT_PATTERN = Pattern.compile("\\b([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\\s*=");
    private static final Pattern CLI_OPTION_PATTERN = Pattern.compile("(?<!\\S)--[a-z0-9][a-z0-9-]*\\b");
    private static final Pattern CODE_SYMBOL_PATTERN = Pattern.compile("\\b(?:[A-Z][a-z0-9]+){2,}[A-Z]?[A-Za-z0-9]*\\b");
    private static final Pattern VERSION_IDENTIFIER_PATTERN = Pattern.compile("\\b[vV]?\\d+(?:\\.\\d+){1,3}\\b");

    public List<ExtractedEntity> extract(String chunkText) {
        String safeChunkText = chunkText == null ? "" : chunkText;
        Map<String, ExtractedEntity> entities = new LinkedHashMap<>();

        collectEntities(entities, safeChunkText, ERROR_CODE_PATTERN, ExtractedEntityCategory.ERROR_CODE, null, false);
        collectEntities(entities, safeChunkText, CONFIG_KEY_PATTERN, ExtractedEntityCategory.CONFIG_KEY, null, false);
        collectAssignments(entities, safeChunkText);
        collectEntities(entities, safeChunkText, CLI_OPTION_PATTERN, ExtractedEntityCategory.CLI_OPTION, null, false);
        collectEntities(entities, safeChunkText, CODE_SYMBOL_PATTERN, ExtractedEntityCategory.CODE_SYMBOL, null, false);
        collectEntities(entities, safeChunkText, VERSION_IDENTIFIER_PATTERN, ExtractedEntityCategory.VERSION_IDENTIFIER, null, false);

        return List.copyOf(entities.values());
    }

    private void collectAssignments(Map<String, ExtractedEntity> entities, String text) {
        Matcher matcher = ENV_ASSIGNMENT_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(1);
            putEntity(entities, token, ExtractedEntityCategory.ENV_VAR_NAME, "VALUE_REDACTED");
        }
    }

    private void collectEntities(
            Map<String, ExtractedEntity> entities,
            String text,
            Pattern pattern,
            ExtractedEntityCategory category,
            String redactionReason,
            boolean onlyFirstGroup
    ) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String token = onlyFirstGroup ? matcher.group(1) : matcher.group();
            putEntity(entities, token, category, redactionReason);
        }
    }

    private void putEntity(
            Map<String, ExtractedEntity> entities,
            String canonicalToken,
            ExtractedEntityCategory category,
            String redactionReason
    ) {
        String trimmedToken = canonicalToken == null ? "" : canonicalToken.trim();
        if (trimmedToken.isEmpty()) {
            return;
        }
        String key = category.name() + ":" + trimmedToken;
        entities.putIfAbsent(
                key,
                ExtractedEntity.builder()
                        .canonicalToken(trimmedToken)
                        .normalizedVariants(buildNormalizedVariants(trimmedToken))
                        .category(category)
                        .redactionReason(redactionReason)
                        .build()
        );
    }

    private List<String> buildNormalizedVariants(String canonicalToken) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        String lowered = canonicalToken.toLowerCase(Locale.ROOT);
        variants.add(lowered);

        String withoutCliPrefix = lowered.startsWith("--") ? lowered.substring(2) : lowered;
        if (!withoutCliPrefix.isBlank()) {
            variants.add(withoutCliPrefix);
        }

        String separatorSplit = withoutCliPrefix.replaceAll("[._-]+", " ").replaceAll("\\s+", " ").trim();
        if (!separatorSplit.isBlank()) {
            variants.add(separatorSplit);
        }

        String camelSplit = splitCamelCase(canonicalToken).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (!camelSplit.isBlank()) {
            variants.add(camelSplit);
        }

        String alphanumericOnly = withoutCliPrefix.replaceAll("[^a-z0-9]+", "");
        if (!alphanumericOnly.isBlank()) {
            variants.add(alphanumericOnly);
        }

        return new ArrayList<>(variants);
    }

    private String splitCamelCase(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (i > 0 && Character.isUpperCase(current) && Character.isLowerCase(value.charAt(i - 1))) {
                builder.append(' ');
            }
            builder.append(current);
        }
        return builder.toString();
    }
}
