package com.josh.interviewj.ragqa.model;

import java.util.List;

public record ContextAssemblyResult(
        List<ContextBlock> selectedBlocks,
        int totalEstimatedTokens,
        int selectedDocumentCount,
        int selectedSectionCount,
        int duplicateFilteredCount,
        int overlapFilteredCount,
        String degradedReason
) {
    public ContextAssemblyResult {
        selectedBlocks = selectedBlocks == null ? List.of() : List.copyOf(selectedBlocks);
        degradedReason = degradedReason == null || degradedReason.isBlank() ? "none" : degradedReason;
    }

    public static ContextAssemblyResult degraded(String reason) {
        return new ContextAssemblyResult(List.of(), 0, 0, 0, 0, 0, reason);
    }
}
