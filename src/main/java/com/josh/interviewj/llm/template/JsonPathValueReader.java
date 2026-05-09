package com.josh.interviewj.llm.template;

import tools.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonPathValueReader {

    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([A-Za-z0-9_]+)(?:\\[(\\d+)])?");

    public JsonNode read(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }

        JsonNode current = root;
        String[] segments = path.split("\\.");
        for (String segment : segments) {
            Matcher matcher = SEGMENT_PATTERN.matcher(segment);
            if (!matcher.matches()) {
                return null;
            }
            current = current.get(matcher.group(1));
            if (current == null) {
                return null;
            }
            if (matcher.group(2) != null) {
                int index = Integer.parseInt(matcher.group(2));
                if (!current.isArray() || index >= current.size()) {
                    return null;
                }
                current = current.get(index);
            }
        }
        return current;
    }
}
