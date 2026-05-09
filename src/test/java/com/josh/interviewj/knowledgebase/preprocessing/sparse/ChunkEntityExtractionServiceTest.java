package com.josh.interviewj.knowledgebase.preprocessing.sparse;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkEntityExtractionServiceTest {

    @Test
    void extract_ErrorCode_ConfigKey_AndCliOption() throws Exception {
        List<?> entities = extract("""
                AUTH_001
                spring.profiles.active
                --tests
                KnowledgeBaseQueryService
                """);

        assertHasEntity(entities, "AUTH_001", "ERROR_CODE", "auth_001", "auth 001");
        assertHasEntity(entities, "spring.profiles.active", "CONFIG_KEY", "spring.profiles.active", "spring profiles active");
        assertHasEntity(entities, "--tests", "CLI_OPTION", "--tests", "tests");
        assertHasEntity(entities, "KnowledgeBaseQueryService", "CODE_SYMBOL", "knowledgebasequeryservice", "knowledge base query service");
    }

    @Test
    void extract_SecretLikeAssignment_RedactsValueSide() throws Exception {
        List<?> entities = extract("""
                DATABASE_URL=postgres://user:secret@localhost:5432/db
                Authorization: Bearer xxxxx
                """);

        assertHasEntity(entities, "DATABASE_URL", "ENV_VAR_NAME", "database_url", "database url");
        assertFalse(containsCanonicalToken(entities, "postgres://user:secret@localhost:5432/db"));
        assertFalse(containsCanonicalToken(entities, "xxxxx"));
    }

    @Test
    void materialize_BuildsEntityTsv_ExactTerms_AndMetadataTrace() throws Exception {
        Class<?> extractorClass = Class.forName("com.josh.interviewj.knowledgebase.preprocessing.sparse.ChunkEntityExtractionService");
        Object extractor = extractorClass.getConstructor().newInstance();
        Class<?> materializerClass = Class.forName("com.josh.interviewj.knowledgebase.preprocessing.sparse.ChunkSparseMaterializer");
        Object materializer = materializerClass
                .getConstructor(extractorClass, tools.jackson.databind.ObjectMapper.class)
                .newInstance(extractor, new tools.jackson.databind.ObjectMapper());
        Method materialize = materializerClass.getMethod("materialize", String.class, String.class);

        Object result = materialize.invoke(
                materializer,
                "AUTH_001 spring.profiles.active --tests KnowledgeBaseQueryService DATABASE_URL=postgres://db",
                "{\"chunkVersion\":\"v1\"}"
        );

        Method sparseEntitiesText = result.getClass().getMethod("sparseEntitiesText");
        Method sparseExactTerms = result.getClass().getMethod("sparseExactTerms");
        Method metadataJson = result.getClass().getMethod("metadataJson");

        String entitiesText = (String) sparseEntitiesText.invoke(result);
        @SuppressWarnings("unchecked")
        List<String> exactTerms = (List<String>) sparseExactTerms.invoke(result);
        String metadata = (String) metadataJson.invoke(result);

        assertTrue(entitiesText.contains("AUTH_001"));
        assertTrue(entitiesText.contains("spring profiles active"));
        assertTrue(exactTerms.contains("AUTH_001"));
        assertTrue(exactTerms.contains("auth_001"));
        assertTrue(exactTerms.contains("knowledge base query service"));
        assertFalse(exactTerms.contains("postgres://db"));
        assertTrue(metadata.contains("\"extractedEntities\""));
        assertTrue(metadata.contains("\"entityCategories\""));
        assertTrue(metadata.contains("\"entityTraceVersion\""));
        assertTrue(metadata.contains("\"entityRedactionSummary\""));
    }

    private List<?> extract(String text) throws Exception {
        Class<?> serviceClass = Class.forName("com.josh.interviewj.knowledgebase.preprocessing.sparse.ChunkEntityExtractionService");
        Object service = serviceClass.getConstructor().newInstance();
        Method extract = serviceClass.getMethod("extract", String.class);
        Object result = extract.invoke(service, text);
        return (List<?>) result;
    }

    private void assertHasEntity(
            List<?> entities,
            String canonicalToken,
            String category,
            String... normalizedVariants
    ) throws Exception {
        for (Object entity : entities) {
            String actualCanonicalToken = String.valueOf(entity.getClass().getMethod("canonicalToken").invoke(entity));
            String actualCategory = String.valueOf(entity.getClass().getMethod("category").invoke(entity));
            if (!canonicalToken.equals(actualCanonicalToken) || !category.equals(actualCategory)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> actualNormalizedVariants = (List<String>) entity.getClass()
                    .getMethod("normalizedVariants")
                    .invoke(entity);
            for (String normalizedVariant : normalizedVariants) {
                assertTrue(actualNormalizedVariants.contains(normalizedVariant));
            }
            return;
        }
        throw new AssertionError("Expected entity not found: " + canonicalToken + " / " + category);
    }

    private boolean containsCanonicalToken(List<?> entities, String candidate) throws Exception {
        for (Object entity : entities) {
            String actualCanonicalToken = String.valueOf(entity.getClass().getMethod("canonicalToken").invoke(entity));
            if (candidate.equals(actualCanonicalToken)) {
                return true;
            }
        }
        return false;
    }
}
