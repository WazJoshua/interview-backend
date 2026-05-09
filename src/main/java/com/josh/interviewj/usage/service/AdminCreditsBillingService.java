package com.josh.interviewj.usage.service;

import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.service.LegacyCreditPolicyProjectionService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.usage.dto.request.AdminUsageEventsQuery;
import com.josh.interviewj.usage.dto.request.AdminCreditPolicyVersionQuery;
import com.josh.interviewj.usage.dto.request.CreateCreditPolicyVersionRequest;
import com.josh.interviewj.usage.dto.request.UpdateUserCreditPolicyRequest;
import com.josh.interviewj.usage.dto.response.AdminCreditLedgerResponse;
import com.josh.interviewj.usage.dto.response.AdminCreditPolicyVersionResponse;
import com.josh.interviewj.usage.dto.response.AdminUserCreditPolicyResponse;
import com.josh.interviewj.usage.model.BillingUnit;
import com.josh.interviewj.usage.model.ChargeBucket;
import com.josh.interviewj.usage.model.PeriodType;
import com.josh.interviewj.usage.model.UsageCreditPolicyVersion;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.model.UserCreditPeriod;
import com.josh.interviewj.usage.model.UserCreditPolicy;
import com.josh.interviewj.usage.repository.UsageCreditPolicyVersionRepository;
import com.josh.interviewj.usage.repository.UserCreditPeriodRepository;
import com.josh.interviewj.usage.repository.UserCreditPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminCreditsBillingService {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final UsageCreditPolicyVersionRepository usageCreditPolicyVersionRepository;
    private final UserCreditPolicyRepository userCreditPolicyRepository;
    private final UserCreditPeriodRepository userCreditPeriodRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CreditFormattingService creditFormattingService;
    private final AdminOperationLogService adminOperationLogService;
    private final LegacyCreditPolicyProjectionService legacyCreditPolicyProjectionService;

    public Page<AdminCreditPolicyVersionResponse> getPolicyVersions(AdminCreditPolicyVersionQuery query) {
        LocalDateTime effectiveAt = nowUtc();
        List<AdminCreditPolicyVersionResponse> filtered = usageCreditPolicyVersionRepository.findAll().stream()
                .filter(version -> query.getPurpose() == null || query.getPurpose().equals(version.getPurpose()))
                .filter(version -> query.getChargeBucket() == null || query.getChargeBucket().equals(version.getChargeBucket().name()))
                .filter(version -> query.getUsageFamily() == null || query.getUsageFamily().equals(version.getUsageFamily().name()))
                .filter(version -> !Boolean.TRUE.equals(query.getCurrentOnly()) || isActive(version, effectiveAt))
                .sorted((left, right) -> right.getEffectiveFrom().compareTo(left.getEffectiveFrom()))
                .map(this::toCreditPolicyVersionResponse)
                .toList();

        PageRequest pageRequest = PageRequest.of(query.getPage(), query.getSize());
        int fromIndex = Math.min((int) pageRequest.getOffset(), filtered.size());
        int toIndex = Math.min(fromIndex + pageRequest.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(fromIndex, toIndex), pageRequest, filtered.size());
    }

    @Transactional
    public AdminCreditPolicyVersionResponse createPolicyVersion(Long actorUserId, CreateCreditPolicyVersionRequest request) {
        BillingUnit billingUnit = parseEnum(BillingUnit.class, request.getBillingUnit(), "billingUnit");
        validatePolicyVersionRatios(billingUnit, request);

        ChargeBucket chargeBucket = parseEnum(ChargeBucket.class, request.getChargeBucket(), "chargeBucket");
        UsageFamily usageFamily = parseEnum(UsageFamily.class, request.getUsageFamily(), "usageFamily");
        LocalDateTime effectiveFrom = toUtcLocalDateTime(request.getEffectiveFrom());
        LocalDateTime effectiveTo = toUtcLocalDateTime(request.getEffectiveTo());
        List<UsageCreditPolicyVersion> overlaps = usageCreditPolicyVersionRepository.findOverlappingVersions(
                request.getPurpose(),
                chargeBucket.name(),
                usageFamily.name(),
                effectiveFrom,
                effectiveTo,
                null
        );
        truncateOverlappingPolicyVersions(actorUserId, overlaps, effectiveFrom);

        UsageCreditPolicyVersion version = usageCreditPolicyVersionRepository.save(UsageCreditPolicyVersion.builder()
                .purpose(request.getPurpose())
                .chargeBucket(chargeBucket)
                .usageFamily(usageFamily)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .billingUnit(billingUnit)
                .promptTokenRatio(request.getPromptTokenRatio())
                .completionTokenRatio(request.getCompletionTokenRatio())
                .cachedTokenRatio(request.getCachedTokenRatio())
                .requestRatio(request.getRequestRatio())
                .metadata(normalizeMetadata(request.getMetadata()))
                .build());
        AdminCreditPolicyVersionResponse response = toCreditPolicyVersionResponse(version);
        adminOperationLogService.recordCreate(
                actorUserId,
                AdminOperationResourceType.CREDIT_POLICY_VERSION,
                response.getId(),
                null,
                response,
                Map.of(
                        "purpose", request.getPurpose(),
                        "chargeBucket", chargeBucket.name(),
                        "usageFamily", usageFamily.name()
                )
        );
        return response;
    }

    public Page<AdminCreditLedgerResponse> getCreditLedger(AdminUsageEventsQuery query) {
        PageRequest pageRequest = PageRequest.of(query.getPage(), query.getSize());
        PeriodWindow periodWindow = resolveWindow(query.getFrom(), query.getTo());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", pageRequest.getPageSize())
                .addValue("offset", pageRequest.getOffset());

        String baseSql = creditLedgerBaseSql(query, params, periodWindow);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + baseSql, params, Long.class);
        List<AdminCreditLedgerResponse> content = jdbcTemplate.query("""
                        SELECT
                            cl.id,
                            e.external_id AS usage_event_external_id,
                            u.external_id AS user_external_id,
                            e.purpose,
                            e.usage_family,
                            e.provider,
                            e.model_code,
                            cl.charge_bucket,
                            cl.charge_status,
                            cl.charged_credits_micros,
                            cl.credit_policy_version_id,
                            cl.created_at
                        """ + baseSql + """
                        ORDER BY e.occurred_at DESC, cl.id DESC
                        LIMIT :limit OFFSET :offset
                        """,
                params,
                (rs, rowNum) -> AdminCreditLedgerResponse.builder()
                        .id(String.valueOf(rs.getLong("id")))
                        .usageEventId(rs.getString("usage_event_external_id"))
                        .userId(rs.getString("user_external_id"))
                        .purpose(rs.getString("purpose"))
                        .usageFamily(rs.getString("usage_family"))
                        .provider(rs.getString("provider"))
                        .modelCode(rs.getString("model_code"))
                        .chargeBucket(rs.getString("charge_bucket"))
                        .chargeStatus(rs.getString("charge_status"))
                        .chargedCreditsMicros(longOrNull(rs.getObject("charged_credits_micros")))
                        .chargedCredits(creditFormattingService.formatCreditsMicrosNullable(longOrNull(rs.getObject("charged_credits_micros"))))
                        .creditPolicyVersionId(stringify(rs.getObject("credit_policy_version_id")))
                        .createdAt(toOffsetDateTime(rs.getTimestamp("created_at").toLocalDateTime()))
                        .build());

        return new PageImpl<>(content, pageRequest, total == null ? 0L : total);
    }

    public AdminUserCreditPolicyResponse getUserCreditPolicy(UUID userExternalId) {
        User user = userRepository.findByExternalId(userExternalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_003, "User not found"));
        legacyCreditPolicyProjectionService.projectForUser(user.getId());

        List<UserCreditPolicy> policies = userCreditPolicyRepository.findByUserIdOrderByEffectiveFromAsc(user.getId());
        if (policies.isEmpty()) {
            throw new BusinessException("ADMIN_CREDIT_003", "Target user credit policy is not configured");
        }

        LocalDateTime effectiveAt = nowUtc();
        UserCreditPolicy activePolicy = policies.stream().filter(policy -> isActive(policy, effectiveAt)).findFirst().orElse(null);
        UserCreditPolicy pendingPolicy = policies.stream().filter(policy -> policy.getEffectiveFrom().isAfter(effectiveAt)).findFirst().orElse(null);
        PeriodWindow periodWindow = currentMonthlyPeriod(effectiveAt);
        UserCreditPeriod currentPeriod = userCreditPeriodRepository
                .findByUserIdAndPeriodTypeAndPeriodStartAndPeriodEnd(
                        user.getId(),
                        PeriodType.MONTHLY,
                        periodWindow.periodStart(),
                        periodWindow.periodEnd()
                )
                .orElse(null);

        return AdminUserCreditPolicyResponse.builder()
                .userId(user.getExternalId().toString())
                .activePolicy(activePolicy == null ? null : toUserPolicyResponse(activePolicy))
                .pendingPolicy(pendingPolicy == null ? null : toUserPolicyResponse(pendingPolicy))
                .currentPeriod(toCurrentPeriodResponse(periodWindow, currentPeriod))
                .build();
    }

    @Transactional
    public AdminUserCreditPolicyResponse updateUserCreditPolicy(Long actorUserId, UUID userExternalId, UpdateUserCreditPolicyRequest request) {
        AdminUserCreditPolicyResponse beforeSnapshot = safeGetUserCreditPolicy(userExternalId);
        User user = userRepository.findByExternalId(userExternalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_003, "User not found"));

        LocalDateTime effectiveFrom = toUtcLocalDateTime(request.getEffectiveFrom());
        LocalDateTime effectiveTo = toUtcLocalDateTime(request.getEffectiveTo());
        List<UserCreditPolicy> overlaps = userCreditPolicyRepository.findOverlappingPolicies(
                user.getId(),
                effectiveFrom,
                effectiveTo,
                null
        );
        truncateOverlappingUserPolicies(overlaps, effectiveFrom);

        userCreditPolicyRepository.save(UserCreditPolicy.builder()
                .userId(user.getId())
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .resumeCreditsLimitMicros(request.getResumeCreditsLimitMicros())
                .kbQueryCreditsLimitMicros(request.getKbQueryCreditsLimitMicros())
                .kbIngestionCreditsLimitMicros(request.getKbIngestionCreditsLimitMicros())
                .interviewCreditsLimitMicros(request.getInterviewCreditsLimitMicros())
                .build());
        AdminUserCreditPolicyResponse response = getUserCreditPolicy(userExternalId);
        adminOperationLogService.recordUpdate(
                actorUserId,
                AdminOperationResourceType.USER_CREDIT_POLICY,
                userExternalId.toString(),
                null,
                beforeSnapshot,
                response,
                Map.of("targetUserId", userExternalId.toString())
        );
        return response;
    }

    private void validatePolicyVersionRatios(BillingUnit billingUnit, CreateCreditPolicyVersionRequest request) {
        validateNonNegative(request.getPromptTokenRatio(), "promptTokenRatio");
        validateNonNegative(request.getCompletionTokenRatio(), "completionTokenRatio");
        validateNonNegative(request.getCachedTokenRatio(), "cachedTokenRatio");
        validateNonNegative(request.getRequestRatio(), "requestRatio");

        boolean hasTokenRatio = request.getPromptTokenRatio() != null
                || request.getCompletionTokenRatio() != null
                || request.getCachedTokenRatio() != null;
        boolean hasRequestRatio = request.getRequestRatio() != null;

        if (billingUnit == BillingUnit.TOKEN && !hasTokenRatio) {
            throw new BusinessException("ADMIN_CREDIT_001", "TOKEN billing requires at least one token ratio");
        }
        if (billingUnit == BillingUnit.REQUEST && !hasRequestRatio) {
            throw new BusinessException("ADMIN_CREDIT_001", "REQUEST billing requires requestRatio");
        }
        if (billingUnit == BillingUnit.TOKEN_AND_REQUEST && (!hasTokenRatio || !hasRequestRatio)) {
            throw new BusinessException("ADMIN_CREDIT_001", "TOKEN_AND_REQUEST billing requires both token and request ratios");
        }
    }

    private void validateNonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new BusinessException("VALIDATION_ERROR", field + " must be non-negative");
        }
    }

    private AdminCreditPolicyVersionResponse toCreditPolicyVersionResponse(UsageCreditPolicyVersion version) {
        return AdminCreditPolicyVersionResponse.builder()
                .id(String.valueOf(version.getId()))
                .purpose(version.getPurpose())
                .chargeBucket(version.getChargeBucket().name())
                .usageFamily(version.getUsageFamily().name())
                .effectiveFrom(toOffsetDateTime(version.getEffectiveFrom()))
                .effectiveTo(toOffsetDateTime(version.getEffectiveTo()))
                .billingUnit(version.getBillingUnit().name())
                .promptTokenRatio(creditFormattingService.formatRatio(version.getPromptTokenRatio()))
                .completionTokenRatio(creditFormattingService.formatRatio(version.getCompletionTokenRatio()))
                .cachedTokenRatio(creditFormattingService.formatRatio(version.getCachedTokenRatio()))
                .requestRatio(creditFormattingService.formatRatio(version.getRequestRatio()))
                .metadata(readMetadata(version.getMetadata()))
                .createdAt(toOffsetDateTime(version.getCreatedAt()))
                .updatedAt(toOffsetDateTime(version.getUpdatedAt()))
                .build();
    }

    private AdminUserCreditPolicyResponse.Policy toUserPolicyResponse(UserCreditPolicy policy) {
        return AdminUserCreditPolicyResponse.Policy.builder()
                .id(String.valueOf(policy.getId()))
                .effectiveFrom(toOffsetDateTime(policy.getEffectiveFrom()))
                .effectiveTo(toOffsetDateTime(policy.getEffectiveTo()))
                .resumeCreditsLimit(creditFormattingService.formatCreditsMicros(policy.getResumeCreditsLimitMicros()))
                .kbQueryCreditsLimit(creditFormattingService.formatCreditsMicros(policy.getKbQueryCreditsLimitMicros()))
                .kbIngestionCreditsLimit(creditFormattingService.formatCreditsMicros(policy.getKbIngestionCreditsLimitMicros()))
                .interviewCreditsLimit(creditFormattingService.formatCreditsMicros(policy.getInterviewCreditsLimitMicros()))
                .resumeCreditsLimitMicros(policy.getResumeCreditsLimitMicros())
                .kbQueryCreditsLimitMicros(policy.getKbQueryCreditsLimitMicros())
                .kbIngestionCreditsLimitMicros(policy.getKbIngestionCreditsLimitMicros())
                .interviewCreditsLimitMicros(policy.getInterviewCreditsLimitMicros())
                .createdAt(toOffsetDateTime(policy.getCreatedAt()))
                .updatedAt(toOffsetDateTime(policy.getUpdatedAt()))
                .build();
    }

    private AdminUserCreditPolicyResponse.CurrentPeriod toCurrentPeriodResponse(
            PeriodWindow periodWindow,
            UserCreditPeriod currentPeriod
    ) {
        long resumeUsed = currentPeriod == null ? 0L : currentPeriod.getResumeCreditsUsedMicros();
        long kbQueryUsed = currentPeriod == null ? 0L : currentPeriod.getKbQueryCreditsUsedMicros();
        long kbIngestionUsed = currentPeriod == null ? 0L : currentPeriod.getKbIngestionCreditsUsedMicros();
        long interviewUsed = currentPeriod == null ? 0L : currentPeriod.getInterviewCreditsUsedMicros();
        return AdminUserCreditPolicyResponse.CurrentPeriod.builder()
                .periodType(PeriodType.MONTHLY.name())
                .periodStart(toOffsetDateTime(periodWindow.periodStart()))
                .periodEnd(toOffsetDateTime(periodWindow.periodEnd()))
                .resumeCreditsUsed(creditFormattingService.formatCreditsMicros(resumeUsed))
                .kbQueryCreditsUsed(creditFormattingService.formatCreditsMicros(kbQueryUsed))
                .kbIngestionCreditsUsed(creditFormattingService.formatCreditsMicros(kbIngestionUsed))
                .interviewCreditsUsed(creditFormattingService.formatCreditsMicros(interviewUsed))
                .resumeCreditsUsedMicros(resumeUsed)
                .kbQueryCreditsUsedMicros(kbQueryUsed)
                .kbIngestionCreditsUsedMicros(kbIngestionUsed)
                .interviewCreditsUsedMicros(interviewUsed)
                .build();
    }

    private boolean isActive(UsageCreditPolicyVersion version, LocalDateTime effectiveAt) {
        return !version.getEffectiveFrom().isAfter(effectiveAt)
                && (version.getEffectiveTo() == null || version.getEffectiveTo().isAfter(effectiveAt));
    }

    private boolean isActive(UserCreditPolicy policy, LocalDateTime effectiveAt) {
        return !policy.getEffectiveFrom().isAfter(effectiveAt)
                && (policy.getEffectiveTo() == null || policy.getEffectiveTo().isAfter(effectiveAt));
    }

    private String normalizeMetadata(String metadata) {
        return metadata == null || metadata.isBlank() ? "{}" : metadata;
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

    private PeriodWindow resolveWindow(OffsetDateTime from, OffsetDateTime to) {
        if (from == null && to == null) {
            return currentMonthlyPeriod(nowUtc());
        }
        return new PeriodWindow(toUtcLocalDateTime(from), toUtcLocalDateTime(to));
    }

    private PeriodWindow currentMonthlyPeriod(LocalDateTime effectiveAt) {
        LocalDateTime periodStart = effectiveAt.withDayOfMonth(1).toLocalDate().atStartOfDay();
        return new PeriodWindow(periodStart, periodStart.plusMonths(1));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private LocalDateTime toUtcLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private String creditLedgerBaseSql(
            AdminUsageEventsQuery query,
            MapSqlParameterSource params,
            PeriodWindow periodWindow
    ) {
        StringBuilder sql = new StringBuilder("""
                FROM llm_usage_credit_ledger cl
                JOIN llm_usage_event e ON e.id = cl.usage_event_id
                JOIN users u ON u.id = e.user_id
                WHERE 1 = 1
                """);
        appendCreatedAtPredicate(sql, params, periodWindow);
        appendEqualsFilter(sql, params, "u.external_id", "userId", query.getUserId());
        appendEqualsFilter(sql, params, "e.purpose", "purpose", blankToNull(query.getPurpose()));
        appendEqualsFilter(sql, params, "e.usage_family", "usageFamily", blankToNull(query.getUsageFamily()));
        appendEqualsFilter(sql, params, "cl.charge_bucket", "chargeBucket", blankToNull(query.getChargeBucket()));
        appendEqualsFilter(sql, params, "cl.charge_status", "chargeStatus", blankToNull(query.getChargeStatus()));
        return sql.toString();
    }

    private void appendCreatedAtPredicate(
            StringBuilder sql,
            MapSqlParameterSource params,
            PeriodWindow periodWindow
    ) {
        if (periodWindow.periodStart() != null) {
            sql.append("  AND e.occurred_at >= :from\n");
            params.addValue("from", periodWindow.periodStart());
        }
        if (periodWindow.periodEnd() != null) {
            sql.append("  AND e.occurred_at < :to\n");
            params.addValue("to", periodWindow.periodEnd());
        }
    }

    private void appendEqualsFilter(
            StringBuilder sql,
            MapSqlParameterSource params,
            String column,
            String paramName,
            Object value
    ) {
        if (value == null) {
            return;
        }
        sql.append("  AND ").append(column).append(" = :").append(paramName).append('\n');
        params.addValue(paramName, value);
    }

    private void truncateOverlappingPolicyVersions(Long actorUserId, List<UsageCreditPolicyVersion> overlaps, LocalDateTime effectiveFrom) {
        if (overlaps.isEmpty()) {
            return;
        }

        if (overlaps.size() == 1) {
            UsageCreditPolicyVersion predecessor = overlaps.getFirst();
            if (predecessor.getEffectiveTo() == null
                    && predecessor.getEffectiveFrom().isBefore(effectiveFrom)
                    && effectiveFrom.isAfter(nowUtc())) {
                AdminCreditPolicyVersionResponse beforeSnapshot = toCreditPolicyVersionResponse(predecessor);
                predecessor.setEffectiveTo(effectiveFrom);
                UsageCreditPolicyVersion savedPredecessor = usageCreditPolicyVersionRepository.save(predecessor);
                adminOperationLogService.recordUpdate(
                        actorUserId,
                        AdminOperationResourceType.CREDIT_POLICY_VERSION,
                        String.valueOf(savedPredecessor.getId()),
                        null,
                        beforeSnapshot,
                        toCreditPolicyVersionResponse(savedPredecessor),
                        successorTruncationMetadata(
                                savedPredecessor.getPurpose(),
                                savedPredecessor.getChargeBucket().name(),
                                savedPredecessor.getUsageFamily().name(),
                                effectiveFrom
                        )
                );
                return;
            }
        }

        throw new BusinessException("ADMIN_CREDIT_002", "Credit policy version time range overlaps");
    }

    private void truncateOverlappingUserPolicies(List<UserCreditPolicy> overlaps, LocalDateTime effectiveFrom) {
        if (overlaps.isEmpty()) {
            return;
        }

        if (overlaps.size() == 1) {
            UserCreditPolicy predecessor = overlaps.getFirst();
            if (predecessor.getEffectiveTo() == null && predecessor.getEffectiveFrom().isBefore(effectiveFrom)) {
                predecessor.setEffectiveTo(effectiveFrom);
                userCreditPolicyRepository.save(predecessor);
                return;
            }
        }

        throw new BusinessException("USER_CREDIT_002", "User credit policy time range overlaps");
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumType, String rawValue, String fieldName) {
        try {
            return Enum.valueOf(enumType, rawValue);
        } catch (RuntimeException exception) {
            throw new BusinessException("VALIDATION_ERROR", "Unsupported " + fieldName);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private AdminUserCreditPolicyResponse safeGetUserCreditPolicy(UUID userExternalId) {
        try {
            return getUserCreditPolicy(userExternalId);
        } catch (BusinessException exception) {
            if (ErrorCode.ADMIN_CREDIT_003.equals(exception.getErrorCode())) {
                return null;
            }
            throw exception;
        }
    }

    private Map<String, Object> successorTruncationMetadata(
            String purpose,
            String chargeBucket,
            String usageFamily,
            LocalDateTime successorEffectiveFrom
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("purpose", purpose);
        metadata.put("chargeBucket", chargeBucket);
        metadata.put("usageFamily", usageFamily);
        metadata.put("changeType", "TRUNCATED_BY_SUCCESSOR_CREATE");
        metadata.put("successorEffectiveFrom", successorEffectiveFrom.toString());
        return metadata;
    }

    private record PeriodWindow(LocalDateTime periodStart, LocalDateTime periodEnd) {
    }
}
