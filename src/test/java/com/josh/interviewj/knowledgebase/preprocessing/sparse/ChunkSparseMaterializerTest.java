package com.josh.interviewj.knowledgebase.preprocessing.sparse;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSparseMaterializerTest {

    @Test
    void materialize_BuildsEntityTsv_ExactTerms_AndMetadataTrace() throws Exception {
        Object result = materialize(
                "AUTH_001 spring.profiles.active --tests KnowledgeBaseQueryService DATABASE_URL=postgres://db",
                "{\"chunkVersion\":\"v1\"}"
        );

        String sparseContentText = (String) accessor(result, "sparseContentText").invoke(result);
        String sparseEntitiesText = (String) accessor(result, "sparseEntitiesText").invoke(result);
        @SuppressWarnings("unchecked")
        List<String> sparseExactTerms = (List<String>) accessor(result, "sparseExactTerms").invoke(result);
        String metadataJson = (String) accessor(result, "metadataJson").invoke(result);

        assertTrue(sparseContentText.contains("AUTH_001"));
        assertTrue(sparseEntitiesText.contains("spring profiles active"));
        assertTrue(sparseEntitiesText.contains("knowledge base query service"));
        assertTrue(sparseExactTerms.contains("AUTH_001"));
        assertTrue(sparseExactTerms.contains("auth_001"));
        assertTrue(sparseExactTerms.contains("knowledge base query service"));
        assertFalse(sparseExactTerms.contains("profiles"));
        assertFalse(sparseExactTerms.contains("postgres://db"));
        assertTrue(metadataJson.contains("\"extractedEntities\""));
        assertTrue(metadataJson.contains("\"entityCategories\""));
        assertTrue(metadataJson.contains("\"entityTraceVersion\""));
        assertTrue(metadataJson.contains("\"entityRedactionSummary\""));
    }

    private Object materialize(String chunkText, String metadataJson) throws Exception {
        Class<?> extractorClass = Class.forName("com.josh.interviewj.knowledgebase.preprocessing.sparse.ChunkEntityExtractionService");
        Object extractor = extractorClass.getConstructor().newInstance();
        Class<?> materializerClass = Class.forName("com.josh.interviewj.knowledgebase.preprocessing.sparse.ChunkSparseMaterializer");
        Object materializer = materializerClass
                .getConstructor(extractorClass, tools.jackson.databind.ObjectMapper.class)
                .newInstance(extractor, new tools.jackson.databind.ObjectMapper());
        Method materialize = materializerClass.getMethod("materialize", String.class, String.class);
        return materialize.invoke(materializer, chunkText, metadataJson);
    }

    private Method accessor(Object target, String methodName) throws NoSuchMethodException {
        return target.getClass().getMethod(methodName);
    }
}
