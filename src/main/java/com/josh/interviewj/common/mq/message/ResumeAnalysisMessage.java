package com.josh.interviewj.common.mq.message;

import java.util.UUID;

public record ResumeAnalysisMessage(
        Long reportId,
        Long resumeId,
        UUID resumeExternalId,
        Long outboxId
) {
}
