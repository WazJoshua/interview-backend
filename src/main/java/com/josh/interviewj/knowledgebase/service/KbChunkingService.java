package com.josh.interviewj.knowledgebase.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits raw document text into overlapped chunks for embedding and retrieval.
 */
@Service
public class KbChunkingService {

    private static final int CHUNK_CHARS = 1200;
    private static final int OVERLAP_CHARS = 200;

    /**
     * Breaks text into fixed-size chunks with overlap to preserve local context.
     *
     * @param rawText parsed document text
     * @return ordered chunk parts
     */
    public List<ChunkPart> chunk(String rawText) {
        String safeText = rawText == null ? "" : rawText.strip();
        if (safeText.isEmpty()) {
            return List.of();
        }

        List<ChunkPart> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < safeText.length()) {
            int end = Math.min(start + CHUNK_CHARS, safeText.length());
            String content = safeText.substring(start, end);
            chunks.add(new ChunkPart(index, content, start, end, content.length()));
            if (end >= safeText.length()) {
                break;
            }
            start = Math.max(0, end - OVERLAP_CHARS);
            index++;
        }
        return chunks;
    }

    /**
     * Immutable chunk descriptor used during ingestion.
     *
     * @param chunkIndex zero-based chunk index
     * @param content chunk content
     * @param startPosition start offset in source text
     * @param endPosition end offset in source text
     * @param tokenCount approximate token count placeholder
     */
    public record ChunkPart(int chunkIndex, String content, int startPosition, int endPosition, int tokenCount) {
    }
}
