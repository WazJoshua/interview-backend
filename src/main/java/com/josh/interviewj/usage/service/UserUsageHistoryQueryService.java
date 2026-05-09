package com.josh.interviewj.usage.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.usage.dto.request.UserUsageHistoryQuery;
import com.josh.interviewj.usage.dto.response.UserUsageHistoryItemResponse;
import com.josh.interviewj.usage.model.UsageEntryType;
import com.josh.interviewj.usage.model.UsageHistoryCategory;
import com.josh.interviewj.usage.model.UsageHistoryWindowType;
import com.josh.interviewj.usage.repository.UserUsageHistoryReadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserUsageHistoryQueryService {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final UserUsageHistoryReadRepository userUsageHistoryReadRepository;
    private final CreditsBalanceSnapshotService creditsBalanceSnapshotService;
    private final CreditFormattingService creditFormattingService;

    public Page<UserUsageHistoryItemResponse> getCurrentUserHistory(String username, UserUsageHistoryQuery query) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003, "User not found"));
        PageRequest pageRequest = PageRequest.of(query.getPage(), query.getSize());
        WindowRange windowRange = resolveWindow(user.getId(), query);
        return userUsageHistoryReadRepository.findHistory(
                        user.getId(),
                        windowRange.from(),
                        windowRange.to(),
                        query.getCategory(),
                        query.getSourceType(),
                        pageRequest
                )
                .map(this::toResponse);
    }

    private WindowRange resolveWindow(Long userId, UserUsageHistoryQuery query) {
        UsageHistoryWindowType windowType = query.resolvedWindowType();
        LocalDateTime now = nowUtc();
        return switch (windowType) {
            case DEFAULT, CURRENT_SUBSCRIPTION_PERIOD -> {
                CreditsBalanceSnapshot snapshot = creditsBalanceSnapshotService.getSnapshot(userId);
                if (snapshot.hasActiveSubscription()) {
                    yield new WindowRange(
                            snapshot.openContract().getCurrentPeriodStart(),
                            snapshot.openContract().getCurrentPeriodEnd()
                    );
                }
                yield windowType == UsageHistoryWindowType.CURRENT_SUBSCRIPTION_PERIOD
                        ? new WindowRange(now.minusDays(30), now)
                        : new WindowRange(now.minusDays(30), now);
            }
            case LAST_30_DAYS -> new WindowRange(now.minusDays(30), now);
            case LAST_90_DAYS -> new WindowRange(now.minusDays(90), now);
            case ALL -> new WindowRange(null, null);
            case CUSTOM -> new WindowRange(toUtc(query.getFrom()), toUtc(query.getTo()));
        };
    }

    private UserUsageHistoryItemResponse toResponse(UserUsageHistoryReadRepository.HistoryRow row) {
        return UserUsageHistoryItemResponse.builder()
                .id(row.id())
                .entryType(row.entryType())
                .category(row.category())
                .categoryLabel(categoryLabel(row.category()))
                .occurredAt(toOffset(row.occurredAt()))
                .creditsDeltaMicros(row.creditsDeltaMicros())
                .creditsDelta(creditFormattingService.formatCreditsMicros(row.creditsDeltaMicros()))
                .sourceType(row.sourceType())
                .sourceBreakdown(sourceBreakdown(row))
                .usage(usageDetails(row))
                .grant(grantDetails(row))
                .adjustment(adjustmentDetails(row))
                .rejection(rejectionDetails(row))
                .build();
    }

    private UserUsageHistoryItemResponse.SourceBreakdown sourceBreakdown(UserUsageHistoryReadRepository.HistoryRow row) {
        if (row.entryType() != UsageEntryType.USAGE) {
            return null;
        }
        Long subscriptionAllocatedMicros = row.subscriptionAllocatedMicros();
        Long purchasedAllocatedMicros = row.purchasedAllocatedMicros();
        if ((subscriptionAllocatedMicros == null || subscriptionAllocatedMicros == 0L)
                && (purchasedAllocatedMicros == null || purchasedAllocatedMicros == 0L)) {
            return null;
        }
        return UserUsageHistoryItemResponse.SourceBreakdown.builder()
                .subscriptionAllocatedMicros(subscriptionAllocatedMicros)
                .subscriptionAllocated(creditFormattingService.formatCreditsMicrosNullable(subscriptionAllocatedMicros))
                .purchasedAllocatedMicros(purchasedAllocatedMicros)
                .purchasedAllocated(creditFormattingService.formatCreditsMicrosNullable(purchasedAllocatedMicros))
                .build();
    }

    private UserUsageHistoryItemResponse.UsageDetails usageDetails(UserUsageHistoryReadRepository.HistoryRow row) {
        if (row.entryType() != UsageEntryType.USAGE && row.entryType() != UsageEntryType.USAGE_REJECTED) {
            return null;
        }
        return UserUsageHistoryItemResponse.UsageDetails.builder()
                .usageFamily(row.usageFamily())
                .chargeBucket(row.chargeBucket())
                .resourceType(row.resourceType())
                .resourceExternalId(row.resourceExternalId())
                .operationId(row.operationId())
                .build();
    }

    private UserUsageHistoryItemResponse.GrantDetails grantDetails(UserUsageHistoryReadRepository.HistoryRow row) {
        if (row.entryType() != UsageEntryType.GRANT) {
            return null;
        }
        return UserUsageHistoryItemResponse.GrantDetails.builder()
                .eventType(row.billingEventType())
                .sourceType(row.billingSourceType())
                .sourceId(row.billingSourceId())
                .bucketCode(row.billingBucketCode())
                .metadata(readMetadata(row.billingMetadata()))
                .build();
    }

    private UserUsageHistoryItemResponse.AdjustmentDetails adjustmentDetails(UserUsageHistoryReadRepository.HistoryRow row) {
        if (row.entryType() != UsageEntryType.ADJUSTMENT) {
            return null;
        }
        return UserUsageHistoryItemResponse.AdjustmentDetails.builder()
                .eventType(row.billingEventType())
                .sourceType(row.billingSourceType())
                .sourceId(row.billingSourceId())
                .metadata(readMetadata(row.billingMetadata()))
                .build();
    }

    private UserUsageHistoryItemResponse.RejectionDetails rejectionDetails(UserUsageHistoryReadRepository.HistoryRow row) {
        if (row.entryType() != UsageEntryType.USAGE_REJECTED) {
            return null;
        }
        return UserUsageHistoryItemResponse.RejectionDetails.builder()
                .reasonCode(row.rejectionReasonCode())
                .reasonMessage(row.rejectionReasonMessage())
                .metadata(readMetadata(row.rejectionMetadata()))
                .build();
    }

    private String categoryLabel(UsageHistoryCategory category) {
        return switch (category) {
            case RESUME -> "Resume";
            case KB_QUERY -> "Knowledge Base Query";
            case KB_INGESTION -> "Knowledge Base Ingestion";
            case INTERVIEW -> "Interview";
            case GRANT -> "Credits Granted";
            case ADJUSTMENT -> "Billing Adjustment";
            case REJECTED -> "Usage Rejected";
        };
    }

    private Map<String, Object> readMetadata(String rawMetadata) {
        if (rawMetadata == null || rawMetadata.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawMetadata, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private LocalDateTime toUtc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record WindowRange(LocalDateTime from, LocalDateTime to) {
    }
}
