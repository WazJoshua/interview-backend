package com.josh.interviewj.knowledgebase.preprocessing.evaluation;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record NoiseReductionEvaluationCase(
        String id,
        Path casePath,
        String fixture,
        String query,
        List<String> expectedAnchors,
        List<String> expectedDocHints,
        List<String> avoidPatterns,
        List<String> avoidReasonCodes
) {

    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    public NoiseReductionEvaluationCase {
        expectedAnchors = expectedAnchors == null ? List.of() : List.copyOf(expectedAnchors);
        expectedDocHints = expectedDocHints == null ? List.of() : List.copyOf(expectedDocHints);
        avoidPatterns = avoidPatterns == null ? List.of() : List.copyOf(avoidPatterns);
        avoidReasonCodes = avoidReasonCodes == null ? List.of() : List.copyOf(avoidReasonCodes);
    }

    public static NoiseReductionEvaluationCase load(Path yamlPath) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = YAML_MAPPER.readValue(yamlPath.toFile(), Map.class);
            String fixture = requiredString(data, "fixture");
            String query = requiredString(data, "query");
            List<String> expectedAnchors = requiredList(data, "expectedAnchors");
            List<String> expectedDocHints = requiredList(data, "expectedDocHints");
            List<String> avoidPatterns = optionalList(data, "avoidPatterns");
            List<String> avoidReasonCodes = optionalList(data, "avoidReasonCodes");
            String fileName = yamlPath.getFileName().toString();
            String id = fileName.endsWith(".yaml") ? fileName.substring(0, fileName.length() - 5) : fileName;
            return new NoiseReductionEvaluationCase(
                    id,
                    yamlPath,
                    fixture,
                    query,
                    expectedAnchors,
                    expectedDocHints,
                    avoidPatterns,
                    avoidReasonCodes
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to load evaluation case: " + yamlPath, ex);
        }
    }

    public static List<NoiseReductionEvaluationCase> loadAllFromResources(String resourceDirectory) {
        try {
            Path directory = Path.of(Objects.requireNonNull(
                    NoiseReductionEvaluationCase.class.getClassLoader().getResource(resourceDirectory),
                    "Missing resource directory: " + resourceDirectory
            ).toURI());
            try (var paths = Files.list(directory)) {
                return paths
                        .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .map(NoiseReductionEvaluationCase::load)
                        .toList();
            }
        } catch (IOException | URISyntaxException ex) {
            throw new IllegalArgumentException("Failed to load evaluation resources: " + resourceDirectory, ex);
        }
    }

    public Path resolveFixturePath() {
        return casePath.getParent().resolve(fixture).normalize();
    }

    private static String requiredString(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return stringValue;
    }

    private static List<String> requiredList(Map<String, Object> data, String field) {
        if (!data.containsKey(field)) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return optionalList(data, field);
    }

    private static List<String> optionalList(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("Field must be a list: " + field);
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
    }
}
