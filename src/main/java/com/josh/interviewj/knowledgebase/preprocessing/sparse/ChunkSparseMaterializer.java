package com.josh.interviewj.knowledgebase.preprocessing.sparse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ChunkSparseMaterializer {

    private static final String ENTITY_TRACE_VERSION = "HYBRID_SPARSE_V1";

    private final ChunkEntityExtractionService chunkEntityExtractionService;
    private final ObjectMapper objectMapper;

    public ChunkSparseMaterialization materialize(String chunkText, String metadataJson) {
        String safeChunkText = chunkText == null ? "" : chunkText;
        List<ExtractedEntity> entities = chunkEntityExtractionService.extract(safeChunkText);
        Set<String> sparseExactTerms = new LinkedHashSet<>();
        StringBuilder sparseEntitiesText = new StringBuilder();

        for (ExtractedEntity entity : entities) {
            appendTerm(sparseEntitiesText, entity.canonicalToken());
            sparseExactTerms.add(entity.canonicalToken());
            for (String normalizedVariant : entity.normalizedVariants()) {
                appendTerm(sparseEntitiesText, normalizedVariant);
                sparseExactTerms.add(normalizedVariant);
            }
        }

        return ChunkSparseMaterialization.builder()
                .sparseContentText(safeChunkText)
                .sparseEntitiesText(sparseEntitiesText.toString())
                .sparseExactTerms(List.copyOf(sparseExactTerms))
                .metadataJson(buildMetadataJson(metadataJson, entities))
                .build();
    }

    private String buildMetadataJson(String metadataJson, List<ExtractedEntity> entities) {
        try {
            ObjectNode metadata = parseMetadata(metadataJson);
            ArrayNode extractedEntities = metadata.putArray("extractedEntities");
            Set<String> categories = new LinkedHashSet<>();
            Set<String> redactionReasons = new LinkedHashSet<>();

            for (ExtractedEntity entity : entities) {
                ObjectNode entityNode = extractedEntities.addObject();
                entityNode.put("canonicalToken", entity.canonicalToken());
                ArrayNode normalizedVariants = entityNode.putArray("normalizedVariants");
                entity.normalizedVariants().forEach(normalizedVariants::add);
                entityNode.put("category", entity.category().name());
                if (entity.redactionReason() != null && !entity.redactionReason().isBlank()) {
                    entityNode.put("redactionReason", entity.redactionReason());
                    redactionReasons.add(entity.redactionReason());
                }
                categories.add(entity.category().name());
            }

            ArrayNode categoryArray = metadata.putArray("entityCategories");
            categories.stream()
                    .sorted()
                    .forEach(categoryArray::add);

            metadata.put("entityTraceVersion", ENTITY_TRACE_VERSION);
            ObjectNode redactionSummary = metadata.putObject("entityRedactionSummary");
            redactionSummary.put("redactedEntityCount", redactionReasons.size());
            ArrayNode reasons = redactionSummary.putArray("reasons");
            redactionReasons.stream()
                    .sorted()
                    .forEach(reasons::add);

            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to materialize sparse chunk metadata", ex);
        }
    }

    private ObjectNode parseMetadata(String metadataJson) throws Exception {
        if (metadataJson == null || metadataJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode parsed = objectMapper.readTree(metadataJson);
        if (parsed instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    private void appendTerm(StringBuilder builder, String value) {
        String normalizedValue = value == null ? "" : value.trim();
        if (normalizedValue.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(normalizedValue);
    }
}
