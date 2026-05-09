package com.josh.interviewj.knowledgebase.preprocessing.lowsignal;

import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class LowSignalProtectionRules {

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\\b[A-Z]{2,}[_-]\\d{3,}\\b");
    private static final Pattern PATH_OR_URL_PATTERN = Pattern.compile("(https?://\\S+)|([A-Za-z]:\\\\\\S+)|(/[^\\s]+)");
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^(npm|pnpm|yarn|gradle|./gradlew|bash|curl|java|python|docker|kubectl)\\b.*");

    public List<LowSignalReasonCode> evaluate(LowSignalBlockContext context) {
        NormalizedBlock block = context.block();
        if (block.type() == NormalizedBlockType.TITLE
                || block.type() == NormalizedBlockType.HEADING
                || block.type() == NormalizedBlockType.CODE
                || block.type() == NormalizedBlockType.TABLE) {
            return List.of(LowSignalReasonCode.PROTECTED_BLOCK_TYPE);
        }
        if (context.features().hasErrorCode()) {
            return List.of(LowSignalReasonCode.PROTECTED_ERROR_CODE);
        }
        if (context.features().hasPathOrUrl() && isShortTechnicalFragment(block)) {
            return List.of(LowSignalReasonCode.PROTECTED_PATH_OR_URL);
        }
        if (context.features().hasCommand() && isShortTechnicalFragment(block)) {
            return List.of(LowSignalReasonCode.PROTECTED_COMMAND);
        }
        if (block.type() == NormalizedBlockType.LIST_ITEM && block.text().length() <= 40) {
            return List.of(LowSignalReasonCode.PROTECTED_TABLE_LABEL);
        }
        return List.of();
    }

    private boolean isShortTechnicalFragment(NormalizedBlock block) {
        return block.type() != NormalizedBlockType.PARAGRAPH || block.text().length() <= 120;
    }

    public boolean hasErrorCode(String text) {
        return ERROR_CODE_PATTERN.matcher(text).find();
    }

    public boolean hasPathOrUrl(String text) {
        return PATH_OR_URL_PATTERN.matcher(text).find();
    }

    public boolean hasCommand(String text) {
        return COMMAND_PATTERN.matcher(text.trim()).matches();
    }
}
