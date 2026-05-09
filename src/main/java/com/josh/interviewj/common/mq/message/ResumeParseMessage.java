package com.josh.interviewj.common.mq.message;

import java.util.UUID;

public record ResumeParseMessage(
        Long resumeId,
        UUID resumeExternalId,
        Long outboxId
) {
}
