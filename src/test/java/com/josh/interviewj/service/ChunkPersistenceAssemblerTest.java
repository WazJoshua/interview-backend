package com.josh.interviewj.service;

import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkCandidate;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkDerivationContext;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkDocumentContext;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkPersistencePayload;
import com.josh.interviewj.knowledgebase.preprocessing.chunking.ChunkSemanticContext;
import com.josh.interviewj.knowledgebase.preprocessing.sparse.ChunkEntityExtractionService;
import com.josh.interviewj.knowledgebase.preprocessing.sparse.ChunkSparseMaterializer;
import com.josh.interviewj.knowledgebase.service.ChunkPersistenceAssembler;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkPersistenceAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChunkEntityExtractionService chunkEntityExtractionService = mock(ChunkEntityExtractionService.class);
    private final ChunkPersistenceAssembler assembler = new ChunkPersistenceAssembler(
            objectMapper,
            new ChunkSparseMaterializer(chunkEntityExtractionService, objectMapper)
    );

    @Test
    void assemble_StructureAwareChunk_PreservesLegacyChunkVersionKey() throws Exception {
        when(chunkEntityExtractionService.extract("Display text")).thenReturn(List.of());
        ChunkCandidate candidate = ChunkCandidate.builder()
                .chunkIndex(0)
                .displayText("Display text")
                .embeddingText("[文档] Test Document\n\nDisplay text")
                .tokenCountEstimate(12)
                .hasParentContext(true)
                .documentContext(ChunkDocumentContext.builder()
                        .documentTitle("Test Document")
                        .sourceType("MARKDOWN")
                        .preprocessingVersion("v2")
                        .build())
                .semanticContext(ChunkSemanticContext.builder()
                        .sectionPath(List.of("Section A"))
                        .blockTypes(List.of("PARAGRAPH"))
                        .pageNumbers(List.of(1))
                        .build())
                .derivationContext(ChunkDerivationContext.builder().build())
                .build();

        ChunkPersistencePayload payload = assembler.assemble(candidate);

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = objectMapper.readValue(payload.metadataJson(), Map.class);
        assertEquals("STRUCTURE_AWARE_V1", metadata.get("chunkVersion"));
        assertEquals("KB_CHUNK_METADATA_V2", metadata.get("chunkMetadataVersion"));
        assertEquals("Test Document", metadata.get("documentTitle"));
        assertEquals("MARKDOWN", metadata.get("sourceType"));
        assertTrue((Boolean) metadata.get("hasParentContext"));
    }
}
