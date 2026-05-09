package com.josh.interviewj.kb;

import com.josh.interviewj.IntegrationTestBase;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.knowledgebase.repository.DocumentChunkRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.ragqa.repository.ChunkSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class KbDocumentRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private KbDocumentRepository kbDocumentRepository;

    @Autowired
    private ChunkSearchRepository chunkSearchRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private KnowledgeBase knowledgeBase;
    private KbDocument completedDocument;
    private KbDocument processingDocument;

    @BeforeEach
    void setUp() {
        documentChunkRepository.deleteAllInBatch();
        kbDocumentRepository.deleteAllInBatch();
        knowledgeBaseRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        owner = userRepository.save(User.builder()
                .username("kb-owner")
                .email("kb-owner@example.com")
                .password("hashed")
                .build());

        User anotherUser = userRepository.save(User.builder()
                .username("kb-other")
                .email("kb-other@example.com")
                .password("hashed")
                .build());

        knowledgeBase = knowledgeBaseRepository.save(KnowledgeBase.builder()
                .userId(owner.getId())
                .name("KB")
                .description("RAG KB")
                .status(KnowledgeBaseStatus.ACTIVE)
                .build());

        KnowledgeBase anotherKnowledgeBase = knowledgeBaseRepository.save(KnowledgeBase.builder()
                .userId(anotherUser.getId())
                .name("Other KB")
                .description("Other")
                .status(KnowledgeBaseStatus.ACTIVE)
                .build());

        completedDocument = kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName("completed.pdf")
                .fileType("application/pdf")
                .fileUrl("mock://completed.pdf")
                .status(KbDocumentStatus.COMPLETED)
                .expectedChunkCount(2)
                .embeddedChunkCount(2)
                .chunkCount(2)
                .build());

        processingDocument = kbDocumentRepository.save(KbDocument.builder()
                .kbId(knowledgeBase.getId())
                .fileName("processing.pdf")
                .fileType("application/pdf")
                .fileUrl("mock://processing.pdf")
                .status(KbDocumentStatus.PROCESSING)
                .expectedChunkCount(1)
                .embeddedChunkCount(0)
                .chunkCount(0)
                .build());

        KbDocument foreignDocument = kbDocumentRepository.save(KbDocument.builder()
                .kbId(anotherKnowledgeBase.getId())
                .fileName("foreign.pdf")
                .fileType("application/pdf")
                .fileUrl("mock://foreign.pdf")
                .status(KbDocumentStatus.COMPLETED)
                .expectedChunkCount(1)
                .embeddedChunkCount(1)
                .chunkCount(1)
                .build());

        upsertChunkWithDefaultSparseMaterialization(
                documentChunkRepository,
                completedDocument.getId(),
                knowledgeBase.getId(),
                "completed-exact",
                0,
                0,
                10,
                10,
                "{}"
        );
        upsertChunkWithDefaultSparseMaterialization(
                documentChunkRepository,
                completedDocument.getId(),
                knowledgeBase.getId(),
                "completed-far",
                1,
                10,
                20,
                10,
                "{}"
        );
        upsertChunkWithDefaultSparseMaterialization(
                documentChunkRepository,
                processingDocument.getId(),
                knowledgeBase.getId(),
                "processing-exact",
                0,
                0,
                10,
                10,
                "{}"
        );
        upsertChunkWithDefaultSparseMaterialization(
                documentChunkRepository,
                foreignDocument.getId(),
                anotherKnowledgeBase.getId(),
                "foreign-exact",
                0,
                0,
                10,
                10,
                "{}"
        );

        documentChunkRepository.updateEmbeddingIfNull(completedDocument.getId(), 0, vectorLiteral(1.0, 0.0));
        documentChunkRepository.updateEmbeddingIfNull(completedDocument.getId(), 1, vectorLiteral(0.0, 1.0));
        documentChunkRepository.updateEmbeddingIfNull(foreignDocument.getId(), 0, vectorLiteral(1.0, 0.0));
    }

    @Test
    void searchCompletedChunks_FiltersIncompleteDocumentsAndOrdersBySimilarity() {
        List<ChunkSearchRepository.CompletedChunkProjection> results = chunkSearchRepository.searchCompletedChunks(
                knowledgeBase.getExternalId(),
                owner.getId(),
                vectorLiteral(1.0, 0.0),
                5
        );

        assertEquals(2, results.size());
        assertEquals(completedDocument.getExternalId(), results.get(0).getDocumentExternalId());
        assertEquals("completed-exact", results.get(0).getContent());
        assertEquals(0, results.get(0).getChunkIndex());
        assertEquals(completedDocument.getExternalId(), results.get(1).getDocumentExternalId());
        assertEquals("completed-far", results.get(1).getContent());
        assertTrue(results.stream().noneMatch(row -> row.getContent().equals("processing-exact")));
        assertTrue(results.stream().noneMatch(row -> row.getContent().equals("foreign-exact")));
        assertTrue(results.get(0).getSimilarity() >= results.get(1).getSimilarity());
    }

    @Test
    void schema_UsesHalfvecExpressionHnswIndexForChunksEmbedding() {
        String indexDefinition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_chunks_embedding_hnsw'",
                String.class
        );

        assertTrue(indexDefinition.contains("halfvec_cosine_ops"));
        assertTrue(indexDefinition.contains("halfvec(2048)"));
        assertTrue(indexDefinition.contains("embedding"));
    }

    private String vectorLiteral(double first, double second) {
        StringBuilder builder = new StringBuilder("[");
        builder.append(first).append(',').append(second);
        for (int index = 2; index < 2048; index++) {
            builder.append(",0.0");
        }
        builder.append(']');
        return builder.toString();
    }
}
