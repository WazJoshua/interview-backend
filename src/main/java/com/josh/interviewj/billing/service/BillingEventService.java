package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.repository.BillingEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingEventService {

    private final BillingSnapshotCodec billingSnapshotCodec;
    private final BillingEventRepository billingEventRepository;

    @Transactional
    public BillingEvent createOrGet(
            Long userId,
            BillingEventType eventType,
            String sourceType,
            String sourceId,
            String idempotencyKey,
            long deltaAmountMicros,
            String bucketCode,
            LocalDateTime occurredAt,
            Map<String, Object> metadata
    ) {
        return billingEventRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> billingEventRepository.save(BillingEvent.builder()
                        .externalId(UUID.randomUUID())
                        .userId(userId)
                        .eventType(eventType)
                        .sourceType(sourceType)
                        .sourceId(sourceId)
                        .idempotencyKey(idempotencyKey)
                        .deltaAmountMicros(deltaAmountMicros)
                        .bucketCode(bucketCode)
                        .occurredAt(occurredAt)
                        .metadata(billingSnapshotCodec.write(metadata == null ? Map.of() : metadata))
                        .build()));
    }
}
