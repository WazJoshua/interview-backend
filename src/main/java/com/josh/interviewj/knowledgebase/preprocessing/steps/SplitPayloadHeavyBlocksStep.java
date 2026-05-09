package com.josh.interviewj.knowledgebase.preprocessing.steps;

import com.josh.interviewj.knowledgebase.preprocessing.model.DocumentSourceType;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import com.josh.interviewj.knowledgebase.preprocessing.pipeline.PreprocessingContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(75)
public class SplitPayloadHeavyBlocksStep extends AbstractDocumentCleaningStep {

    @Override
    public String getName() {
        return "SplitPayloadHeavyBlocks";
    }

    @Override
    public PreprocessingContext apply(PreprocessingContext context) {
        List<NormalizedBlock> transformed = new ArrayList<>();
        for (NormalizedBlock block : context.workingBlocks()) {
            if (block.type() != NormalizedBlockType.PARAGRAPH) {
                transformed.add(block);
                continue;
            }

            SplitCandidate splitCandidate = evaluateSplitCandidate(block);
            if (splitCandidate.shouldSplit()) {
                transformed.add(buildSplitBlock(block, splitCandidate.explanationText(), "explanation", "split"));
                transformed.add(buildSplitBlock(block, splitCandidate.payloadText(), "payload", "split"));
                continue;
            }
            if (context.parsedDocument().sourceType() == DocumentSourceType.PDF && splitCandidate.shouldKeepWithTrace()) {
                transformed.add(block.toBuilder()
                        .metadata(withSplitMetadata(block.metadata(), "kept_unstable_boundary", null))
                        .build());
                continue;
            }
            transformed.add(block);
        }
        return withMetric(context, reindex(transformed), 0, 0, 0);
    }

    private SplitCandidate evaluateSplitCandidate(NormalizedBlock block) {
        List<String> lines = block.text().lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        if (lines.size() < 3) {
            return SplitCandidate.keep();
        }

        int firstPayloadLineIndex = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (isPayloadLike(lines.get(index))) {
                firstPayloadLineIndex = index;
                break;
            }
        }
        if (firstPayloadLineIndex < 2) {
            return SplitCandidate.keepWithTrace();
        }

        List<String> explanationLines = lines.subList(0, firstPayloadLineIndex);
        List<String> payloadLines = lines.subList(firstPayloadLineIndex, lines.size());
        boolean stablePayloadTail = payloadLines.stream().allMatch(this::isPayloadLike);
        if (!stablePayloadTail) {
            return SplitCandidate.keepWithTrace();
        }

        return SplitCandidate.split(
                String.join("\n", explanationLines),
                String.join("\n", payloadLines)
        );
    }

    private boolean isPayloadLike(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 12) {
            return false;
        }
        boolean hasAssignment = trimmed.contains("=") || trimmed.contains("{") || trimmed.contains("}") || trimmed.contains("[") || trimmed.contains("]");
        long digitOrHexCount = trimmed.chars()
                .filter(ch -> Character.isDigit(ch) || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F'))
                .count();
        double digitOrHexRatio = digitOrHexCount / (double) trimmed.length();
        return hasAssignment || digitOrHexRatio >= 0.35D;
    }

    private NormalizedBlock buildSplitBlock(
            NormalizedBlock original,
            String text,
            String role,
            String outcome
    ) {
        return original.toBuilder()
                .text(text)
                .metadata(withSplitMetadata(original.metadata(), outcome, role))
                .build();
    }

    private Map<String, Object> withSplitMetadata(Map<String, Object> originalMetadata, String outcome, String role) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(originalMetadata);
        @SuppressWarnings("unchecked")
        List<String> cleaningActions = metadata.get("cleaningActions") instanceof List<?> existingActions
                ? new ArrayList<>((List<String>) existingActions)
                : new ArrayList<>();
        if (!cleaningActions.contains(getName())) {
            cleaningActions.add(getName());
        }
        metadata.put("cleaningActions", cleaningActions);
        metadata.put("payloadSplitOutcome", outcome);
        if (role != null) {
            metadata.put("payloadSplitRole", role);
        }
        return metadata;
    }

    private List<NormalizedBlock> reindex(List<NormalizedBlock> blocks) {
        List<NormalizedBlock> reindexed = new ArrayList<>(blocks.size());
        for (int index = 0; index < blocks.size(); index++) {
            reindexed.add(blocks.get(index).toBuilder().order(index).build());
        }
        return reindexed;
    }

    private record SplitCandidate(
            boolean shouldSplit,
            boolean shouldKeepWithTrace,
            String explanationText,
            String payloadText
    ) {
        private static SplitCandidate split(String explanationText, String payloadText) {
            return new SplitCandidate(true, false, explanationText, payloadText);
        }

        private static SplitCandidate keepWithTrace() {
            return new SplitCandidate(false, true, null, null);
        }

        private static SplitCandidate keep() {
            return new SplitCandidate(false, false, null, null);
        }
    }
}
