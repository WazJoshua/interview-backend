package com.josh.interviewj.common.mq.message;

import java.util.UUID;

public record KbDocumentMessage(
        Long kbId,
        UUID kbExternalId,
        Long documentId,
        UUID documentExternalId,
        Long outboxId
) {
}
