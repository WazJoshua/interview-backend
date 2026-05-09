package com.josh.interviewj.service;

import com.josh.interviewj.knowledgebase.service.KbChunkingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbChunkingServiceTest {

    private final KbChunkingService kbChunkingService = new KbChunkingService();

    @Test
    void chunk_NullOrBlankInput_ReturnsEmptyList() {
        assertTrue(kbChunkingService.chunk(null).isEmpty());
        assertTrue(kbChunkingService.chunk("   ").isEmpty());
    }

    @Test
    void chunk_TextShorterThanChunkSize_ReturnsSingleChunk() {
        String text = "hello knowledge base";

        List<KbChunkingService.ChunkPart> result = kbChunkingService.chunk(text);

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).chunkIndex());
        assertEquals(text, result.get(0).content());
        assertEquals(0, result.get(0).startPosition());
        assertEquals(text.length(), result.get(0).endPosition());
        assertEquals(text.length(), result.get(0).tokenCount());
    }

    @Test
    void chunk_TextExceedsChunkSize_UsesTwoHundredCharOverlap() {
        String text = "a".repeat(1300);

        List<KbChunkingService.ChunkPart> result = kbChunkingService.chunk(text);

        assertEquals(2, result.size());
        assertEquals(0, result.get(0).chunkIndex());
        assertEquals(1, result.get(1).chunkIndex());
        assertEquals(0, result.get(0).startPosition());
        assertEquals(1200, result.get(0).endPosition());
        assertEquals(1000, result.get(1).startPosition());
        assertEquals(1300, result.get(1).endPosition());
        assertEquals(1200, result.get(0).content().length());
        assertEquals(300, result.get(1).content().length());
        assertEquals(result.get(0).content().substring(1000), result.get(1).content().substring(0, 200));
    }

    @Test
    void chunk_MultiChunkInput_KeepsStableIndicesAndLastChunkBoundary() {
        String text = "b".repeat(2605);

        List<KbChunkingService.ChunkPart> result = kbChunkingService.chunk(text);

        assertEquals(3, result.size());
        assertEquals(0, result.get(0).chunkIndex());
        assertEquals(1, result.get(1).chunkIndex());
        assertEquals(2, result.get(2).chunkIndex());
        assertEquals(0, result.get(0).startPosition());
        assertEquals(1200, result.get(0).endPosition());
        assertEquals(1000, result.get(1).startPosition());
        assertEquals(2200, result.get(1).endPosition());
        assertEquals(2000, result.get(2).startPosition());
        assertEquals(2605, result.get(2).endPosition());
        assertEquals(605, result.get(2).content().length());
    }
}
