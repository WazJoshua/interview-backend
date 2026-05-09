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

/**
 * Evaluation case for structure-aware chunking.
 *
 * <p>Required fields:
 * <ul>
 *   <li>fixture: path to the fixture file</li>
 *   <li>query: the search query</li>
 *   <li>queryType: TITLE_DRIVEN, STEP_DRIVEN, TABLE_DRIVEN, or BODY_SEMANTIC</li>
 *   <li>expectedAnchors: text fragments that must appear in retrieved chunks</li>
 *   <li>expectedSectionHints: section path hints for retrieval</li>
 * </ul>
 */
public record StructureAwareEvaluationCase(
        String id,
        Path casePath,
        String fixture,
        String query,
        String queryType,
        List<String> expectedAnchors,
        List<String> expectedSectionHints
) {

    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    public StructureAwareEvaluationCase {
        expectedAnchors = expectedAnchors == null ? List.of() : List.copyOf(expectedAnchors);
        expectedSectionHints = expectedSectionHints == null ? List.of() : List.copyOf(expectedSectionHints);
    }

    public static StructureAwareEvaluationCase load(Path yamlPath) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = YAML_MAPPER.readValue(yamlPath.toFile(), Map.class);
            String fixture = requiredString(data, "fixture");
            String query = requiredString(data, "query");
            String queryType = requiredString(data, "queryType");
            List<String> expectedAnchors = optionalList(data, "expectedAnchors");
            List<String> expectedSectionHints = optionalList(data, "expectedSectionHints");
            String fileName = yamlPath.getFileName().toString();
            String id = fileName.endsWith(".yaml") ? fileName.substring(0, fileName.length() - 5) : fileName;
            return new StructureAwareEvaluationCase(
                    id,
                    yamlPath,
                    fixture,
                    query,
                    queryType,
                    expectedAnchors,
                    expectedSectionHints
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to load evaluation case: " + yamlPath, ex);
        }
    }

    public static List<StructureAwareEvaluationCase> loadAllFromResources(String resourceDirectory) {
        try {
            Path directory = Path.of(Objects.requireNonNull(
                    StructureAwareEvaluationCase.class.getClassLoader().getResource(resourceDirectory),
                    "Missing resource directory: " + resourceDirectory
            ).toURI());
            try (var paths = Files.list(directory)) {
                return paths
                        .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .map(StructureAwareEvaluationCase::load)
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