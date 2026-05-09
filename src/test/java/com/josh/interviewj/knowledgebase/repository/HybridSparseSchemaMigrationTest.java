package com.josh.interviewj.knowledgebase.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridSparseSchemaMigrationTest {

    private static final Path MIGRATION_FILE = Path.of(
            "src/main/resources/db/migration/V25__add_hybrid_sparse_retrieval_fields.sql"
    );

    @Test
    void migrate_AddsSparseSearchColumnsAndIndexes() throws IOException {
        assertTrue(Files.exists(MIGRATION_FILE));

        String migrationSql = normalizedSql(Files.readString(MIGRATION_FILE));

        assertTrue(migrationSql.contains("alter table document_chunks add column sparse_content_tsv tsvector"));
        assertTrue(migrationSql.contains("alter table document_chunks add column sparse_entities_tsv tsvector"));
        assertTrue(migrationSql.contains("alter table document_chunks add column sparse_exact_terms text[]"));
        assertTrue(migrationSql.contains("alter table kb_documents add column sparse_ready_version varchar(32)"));
        assertTrue(migrationSql.contains("alter table kb_documents add column sparse_ready_at timestamp"));
        assertTrue(migrationSql.contains("create index idx_chunks_sparse_content_tsv on document_chunks using gin (sparse_content_tsv)"));
        assertTrue(migrationSql.contains("create index idx_chunks_sparse_entities_tsv on document_chunks using gin (sparse_entities_tsv)"));
        assertTrue(migrationSql.contains("create index idx_chunks_sparse_exact_terms on document_chunks using gin (sparse_exact_terms)"));
    }

    @Test
    void markSparseReady_AndClearSparseReady_UpdateDocumentReadinessColumns() throws Exception {
        Method markSparseReady = repositoryMethod("markSparseReady", Long.class, String.class, java.time.LocalDateTime.class);
        Method clearSparseReady = repositoryMethod("clearSparseReady", Long.class);

        String markQuery = normalizedSql(repositoryQuery(markSparseReady));
        String clearQuery = normalizedSql(repositoryQuery(clearSparseReady));

        assertTrue(markQuery.contains("update kb_documents"));
        assertTrue(markQuery.contains("set sparse_ready_version = :version"));
        assertTrue(markQuery.contains("sparse_ready_at = :readyAt".toLowerCase(Locale.ROOT)));
        assertTrue(markQuery.contains("where id = :documentId".toLowerCase(Locale.ROOT)));

        assertTrue(clearQuery.contains("update kb_documents"));
        assertTrue(clearQuery.contains("set sparse_ready_version = null"));
        assertTrue(clearQuery.contains("sparse_ready_at = null"));
        assertTrue(clearQuery.contains("where id = :documentId".toLowerCase(Locale.ROOT)));
    }

    @Test
    void kbSparseReady_ReturnsFalseWhenAnyCompletedDocumentIsNotReady() throws Exception {
        Method existsNotReady = repositoryMethod("existsCompletedDocumentWithoutSparseReady", Long.class, String.class);
        String query = normalizedSql(repositoryQuery(existsNotReady));

        assertTrue(query.contains("select exists"));
        assertTrue(query.contains("from kb_documents"));
        assertTrue(query.contains("kb_id = :kbId".toLowerCase(Locale.ROOT)));
        assertTrue(query.contains("status = 'completed'"));
        assertTrue(query.contains("sparse_ready_version is distinct from :version"));
        assertTrue(query.contains("or sparse_ready_at is null"));
    }

    @Test
    void kbSparseReady_TreatsVersionMismatchAsNotReady() throws Exception {
        Method existsNotReady = repositoryMethod("existsCompletedDocumentWithoutSparseReady", Long.class, String.class);
        String query = normalizedSql(repositoryQuery(existsNotReady));

        assertTrue(query.contains("sparse_ready_version is distinct from :version"));
        assertTrue(!query.contains("sparse_ready_version = :version"));
    }

    @Test
    void kbSparseReady_IgnoresProcessingAndFailedDocuments() throws Exception {
        Method existsNotReady = repositoryMethod("existsCompletedDocumentWithoutSparseReady", Long.class, String.class);
        String query = normalizedSql(repositoryQuery(existsNotReady));

        assertTrue(query.contains("status = 'completed'"));
        assertTrue(!query.contains("processing"));
        assertTrue(!query.contains("failed"));
    }

    private Method repositoryMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = KbDocumentRepository.class.getMethod(name, parameterTypes);
        assertTrue(method.isAnnotationPresent(Query.class));
        return method;
    }

    private String repositoryQuery(Method method) {
        Query query = method.getAnnotation(Query.class);
        assertNotNull(query);
        assertTrue(query.nativeQuery());
        return query.value();
    }

    private String normalizedSql(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
