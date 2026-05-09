package com.josh.interviewj.knowledgebase.preprocessing.chunking;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Configuration properties for structure-aware chunking.
 *
 * <p>Configuration prefix: app.kb.preprocessing.chunking
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>structureAwareEnabled defaults to false for safe rollout</li>
 *   <li>shadowReportEnabled can default to true (only affects test/debug)</li>
 *   <li>Trigger thresholds vs max thresholds are distinct:
 *     <ul>
 *       <li>tableSplitTriggerRows: when to split a table internally</li>
 *       <li>tableMaxRowsPerChunk: max rows per resulting chunk</li>
 *       <li>codeSplitTriggerLines: when to split code internally</li>
 *       <li>codeMaxLinesPerChunk: max lines per resulting chunk</li>
 *     </ul>
 *   </li>
 * </ul>
 */
@Data
public class ChunkingProperties {

    // Feature flags
    private boolean structureAwareEnabled = false;
    private boolean shadowReportEnabled = true;

    // Paragraph chunking thresholds
    @Min(100)
    private int paragraphSoftChars = 900;

    @Min(200)
    private int paragraphHardChars = 1500;

    // List chunking thresholds
    @Min(100)
    private int listSoftChars = 1500;

    // Table chunking thresholds
    @Min(10)
    private int tableSplitTriggerRows = 30;

    @Min(5)
    private int tableMaxRowsPerChunk = 20;

    @Min(500)
    private int tableHardChars = 1500;

    // Code chunking thresholds
    @Min(20)
    private int codeSplitTriggerLines = 120;

    @Min(10)
    private int codeMaxLinesPerChunk = 60;

    // Parent context injection limits
    @Min(1)
    private int parentContextMaxLevels = 2;

    @Min(50)
    private int parentContextMaxChars = 180;

    // PDF weak section path support
    private boolean pdfWeakSectionPathEnabled = true;
}