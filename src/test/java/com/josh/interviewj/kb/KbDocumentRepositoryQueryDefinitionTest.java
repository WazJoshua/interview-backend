package com.josh.interviewj.kb;

import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.ragqa.repository.ChunkSearchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbDocumentRepositoryQueryDefinitionTest {

    @Test
    void searchCompletedChunks_UsesHalfvecExpressionForSimilarityAndOrdering() throws NoSuchMethodException {
        Method method = ChunkSearchRepository.class.getMethod(
                "searchCompletedChunks",
                UUID.class,
                Long.class,
                String.class,
                int.class
        );
        Query query = method.getAnnotation(Query.class);

        assertNotNull(query);
        String sql = query.value();
        assertTrue(sql.contains("(1 - ((dc.embedding::halfvec(2048)) <=> CAST(:queryVector AS halfvec(2048)))) AS similarity"));
        assertTrue(sql.contains("ORDER BY (dc.embedding::halfvec(2048)) <=> CAST(:queryVector AS halfvec(2048))"));
        assertTrue(sql.contains("AND kd.status = 'COMPLETED'"));
        assertTrue(sql.contains("AND dc.embedding IS NOT NULL"));
    }

    @Test
    void upsertChunk_WritesMetadataInSameStatement() throws NoSuchMethodException {
        Method method = DocumentChunkRepository.class.getMethod(
                "upsertChunk",
                Long.class,
                Long.class,
                String.class,
                Integer.class,
                Integer.class,
                Integer.class,
                Integer.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        Query query = method.getAnnotation(Query.class);

        assertNotNull(query);
        String sql = query.value();
        assertTrue(sql.contains("metadata"));
        assertTrue(sql.contains("CAST(:metadata AS jsonb)"));
        assertTrue(sql.contains("metadata = EXCLUDED.metadata"));
        assertTrue(sql.contains("to_tsvector('simple', :sparseContentText)"));
        assertTrue(sql.contains("to_tsvector('simple', :sparseEntitiesText)"));
        assertTrue(sql.contains("string_to_array(:sparseExactTermsPayload, chr(31))"));
        assertTrue(sql.contains("sparse_content_tsv = EXCLUDED.sparse_content_tsv"));
    }
}
