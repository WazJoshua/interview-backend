package com.josh.interviewj.ragqa.model;

import java.util.List;

public record ContextBlockAssemblyResult(
        List<ContextBlock> blocks,
        int mergedBlockCount,
        int overlapFilteredCount,
        int sectionFallbackCount,
        boolean degraded,
        String degradedReason
) {
    public ContextBlockAssemblyResult {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        degradedReason = degradedReason == null || degradedReason.isBlank() ? "none" : degradedReason;
    }

    public static ContextBlockAssemblyResult degraded(String reason) {
        return new ContextBlockAssemblyResult(List.of(), 0, 0, 0, true, reason);
    }
}
