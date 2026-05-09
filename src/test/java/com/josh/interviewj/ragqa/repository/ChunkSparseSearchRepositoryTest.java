package com.josh.interviewj.ragqa.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSparseSearchRepositoryTest {

    @Test
    void searchCompletedChunksSparse_EntityHitOutranksContentOnlyHit() throws NoSuchMethodException {
        Method method = ChunkSparseSearchRepository.class.getMethod(
                "searchCompletedChunksSparse",
                UUID.class,
                Long.class,
                String.class,
                String.class,
                String.class,
                String.class,
                double.class,
                double.class,
                double.class,
                int.class
        );
        Query query = method.getAnnotation(Query.class);

        assertNotNull(query);
        String sql = query.value();
        assertTrue(sql.contains("ts_rank_cd"));
        assertTrue(sql.contains(":contentWeight *"));
        assertTrue(sql.contains(":entityWeight *"));
        assertTrue(sql.contains("ORDER BY finalSparseScore DESC"));
        assertTrue(sql.contains("to_tsvector('simple'"));
        assertTrue(sql.contains("plainto_tsquery('simple'"));
    }

    @Test
    void searchCompletedChunksSparse_ExactBoostIsCaseInsensitiveButNotSubstring() throws NoSuchMethodException {
        Method method = ChunkSparseSearchRepository.class.getMethod(
                "searchCompletedChunksSparse",
                UUID.class,
                Long.class,
                String.class,
                String.class,
                String.class,
                String.class,
                double.class,
                double.class,
                double.class,
                int.class
        );
        Query query = method.getAnnotation(Query.class);

        assertNotNull(query);
        String sql = query.value().toLowerCase();
        assertTrue(sql.contains("lower(stored_term) = lower(query_term)"));
        assertTrue(!sql.contains(" like "));
    }
}
