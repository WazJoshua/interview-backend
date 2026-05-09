package com.josh.interviewj.usage.service;

import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.usage.model.UsageRejectionReasonCode;
import com.josh.interviewj.usage.model.UsageRejectionRecord;
import com.josh.interviewj.usage.repository.UsageRejectionRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UsageRejectionRecordingService {

    private final ObjectMapper objectMapper;
    private final UsageRejectionRecordRepository usageRejectionRecordRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UsageRejectionRecord recordPreflightRejected(
            Long userId,
            String chargeBucket,
            String usageFamily,
            String resourceType,
            String resourceExternalId,
            String operationId,
            String businessOperationId,
            UsageRejectionReasonCode reasonCode,
            String reasonMessage,
            Map<String, Object> metadata,
            LocalDateTime occurredAt
    ) {
        return record(
                userId,
                chargeBucket,
                usageFamily,
                resourceType,
                resourceExternalId,
                operationId,
                businessOperationId,
                reasonCode,
                reasonMessage,
                metadata,
                occurredAt
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UsageRejectionRecord recordPreflightRejected(
            Long userId,
            String chargeBucket,
            String usageFamily,
            String resourceType,
            String resourceExternalId,
            String operationId,
            UsageRejectionReasonCode reasonCode,
            String reasonMessage,
            Map<String, Object> metadata,
            LocalDateTime occurredAt
    ) {
        return recordPreflightRejected(
                userId,
                chargeBucket,
                usageFamily,
                resourceType,
                resourceExternalId,
                operationId,
                null,
                reasonCode,
                reasonMessage,
                metadata,
                occurredAt
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UsageRejectionRecord recordDebitRejected(
            UsageOperationContext context,
            String chargeBucket,
            UsageRejectionReasonCode reasonCode,
            String reasonMessage,
            LocalDateTime occurredAt
    ) {
        return record(
                context.userId(),
                chargeBucket,
                context.providerUsage().usageFamily().name(),
                context.resourceType(),
                context.resourceExternalId(),
                context.operationId(),
                context.businessOperationId(),
                reasonCode,
                reasonMessage,
                Map.of("errorCode", ErrorCode.USER_BILLING_001),
                occurredAt
        );
    }

    private UsageRejectionRecord record(
            Long userId,
            String chargeBucket,
            String usageFamily,
            String resourceType,
            String resourceExternalId,
            String operationId,
            String businessOperationId,
            UsageRejectionReasonCode reasonCode,
            String reasonMessage,
            Map<String, Object> metadata,
            LocalDateTime occurredAt
    ) {
        String dedupeKey = buildDedupeKey(userId, operationId, businessOperationId, chargeBucket, reasonCode);
        return usageRejectionRecordRepository.findByDedupeKey(dedupeKey)
                .orElseGet(() -> createOrGet(
                        dedupeKey,
                        userId,
                        chargeBucket,
                        usageFamily,
                        resourceType,
                        resourceExternalId,
                        operationId,
                        businessOperationId,
                        reasonCode,
                        reasonMessage,
                        metadata,
                        occurredAt
                ));
    }

    private UsageRejectionRecord createOrGet(
            String dedupeKey,
            Long userId,
            String chargeBucket,
            String usageFamily,
            String resourceType,
            String resourceExternalId,
            String operationId,
            String businessOperationId,
            UsageRejectionReasonCode reasonCode,
            String reasonMessage,
            Map<String, Object> metadata,
            LocalDateTime occurredAt
    ) {
        try {
            return usageRejectionRecordRepository.save(UsageRejectionRecord.builder()
                    .dedupeKey(dedupeKey)
                    .userId(userId)
                    .chargeBucket(chargeBucket)
                    .usageFamily(usageFamily)
                    .resourceType(resourceType)
                    .resourceExternalId(resourceExternalId)
                    .operationId(operationId)
                    .businessOperationId(businessOperationId)
                    .reasonCode(reasonCode.name())
                    .reasonMessage(reasonMessage)
                    .metadata(writeMetadata(metadata))
                    .occurredAt(occurredAt)
                    .build());
        } catch (DataIntegrityViolationException exception) {
            return usageRejectionRecordRepository.findByDedupeKey(dedupeKey)
                    .orElseThrow(() -> exception);
        }
    }

    private String buildDedupeKey(
            Long userId,
            String operationId,
            String businessOperationId,
            String chargeBucket,
            UsageRejectionReasonCode reasonCode
    ) {
        String dedupeScope = operationId != null && !operationId.isBlank()
                ? operationId
                : businessOperationId;
        return userId + "|" + dedupeScope + "|" + chargeBucket + "|" + reasonCode.name();
    }

    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize usage rejection metadata", exception);
        }
    }
}
