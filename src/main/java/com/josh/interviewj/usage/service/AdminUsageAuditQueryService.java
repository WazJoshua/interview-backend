package com.josh.interviewj.usage.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.usage.dto.request.AdminUsageEventsQuery;
import com.josh.interviewj.usage.dto.request.AdminUsageSummaryQuery;
import com.josh.interviewj.usage.dto.response.AdminUsageEventResponse;
import com.josh.interviewj.usage.dto.response.AdminUsageSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
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

@Service
@RequiredArgsConstructor
public class AdminUsageAuditQueryService {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CreditFormattingService creditFormattingService;

    public Page<AdminUsageEventResponse> getUsageEvents(AdminUsageEventsQuery query) {
        PageRequest pageRequest = PageRequest.of(query.getPage(), query.getSize());
        PeriodWindow periodWindow = resolveWindow(query.getFrom(), query.getTo());
        MapSqlParameterSource params = baseUsageParams(query, periodWindow)
                .addValue("limit", pageRequest.getPageSize())
                .addValue("offset", pageRequest.getOffset());

        String baseSql = usageEventBaseSql(query, periodWindow, params);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + baseSql, params, Long.class);
        List<AdminUsageEventResponse> content = jdbcTemplate.query("""
                        SELECT
                            e.external_id AS event_external_id,
                            u.external_id AS user_external_id,
                            e.usage_family,
                            e.purpose,
                            COALESCE(model_provider.provider_key, provider_ref.provider_key, e.provider) AS provider,
                            COALESCE(model_ref.model_code, e.model_code) AS model_code,
                            e.resource_type,
                            e.resource_external_id,
                            e.operation_id,
                            e.request_count,
                            e.prompt_tokens,
                            e.completion_tokens,
                            e.total_tokens,
                            e.cached_tokens,
                            ch.charge_status AS cost_charge_status,
                            cl.charge_status AS credit_charge_status,
                            cl.charge_bucket,
                            cl.charged_credits_micros,
                            cl.credit_policy_version_id,
                            e.failure_reason,
                            e.metadata,
                            e.dedupe_key,
                            e.occurred_at AS occurred_at,
                            e.created_at AS created_at
                        """ + baseSql + """
                        ORDER BY e.occurred_at DESC, e.id DESC
                        LIMIT :limit OFFSET :offset
                        """,
                params,
                (rs, rowNum) -> AdminUsageEventResponse.builder()
                        .id(rs.getString("event_external_id"))
                        .userId(rs.getString("user_external_id"))
                        .usageFamily(rs.getString("usage_family"))
                        .purpose(rs.getString("purpose"))
                        .provider(rs.getString("provider"))
                        .modelCode(rs.getString("model_code"))
                        .resourceType(rs.getString("resource_type"))
                        .resourceExternalId(rs.getString("resource_external_id"))
                        .operationId(rs.getString("operation_id"))
                        .requestCount(longOrNull(rs.getObject("request_count")))
                        .promptTokens(longOrNull(rs.getObject("prompt_tokens")))
                        .completionTokens(longOrNull(rs.getObject("completion_tokens")))
                        .totalTokens(longOrNull(rs.getObject("total_tokens")))
                        .cachedTokens(longOrNull(rs.getObject("cached_tokens")))
                        .costChargeStatus(rs.getString("cost_charge_status"))
                        .creditChargeStatus(rs.getString("credit_charge_status"))
                        .chargeBucket(rs.getString("charge_bucket"))
                        .chargedCreditsMicros(longOrNull(rs.getObject("charged_credits_micros")))
                        .chargedCredits(creditFormattingService.formatCreditsMicrosNullable(longOrNull(rs.getObject("charged_credits_micros"))))
                        .creditPolicyVersionId(stringify(rs.getObject("credit_policy_version_id")))
                        .usageSource("SDK_TYPED")
                        .status("RECORDED")
                        .failureReason(rs.getString("failure_reason"))
                        .dedupeKey(rs.getString("dedupe_key"))
                        .metadata(readMetadata(rs.getString("metadata")))
                        .occurredAt(toOffsetDateTime(rs.getTimestamp("occurred_at").toLocalDateTime()))
                        .createdAt(toOffsetDateTime(rs.getTimestamp("created_at").toLocalDateTime()))
                        .build());
        return new PageImpl<>(content, pageRequest, total == null ? 0L : total);
    }

    public AdminUsageSummaryResponse getUsageSummary(AdminUsageSummaryQuery query) {
        PeriodWindow periodWindow = resolveWindow(query.getFrom(), query.getTo());
        String dimension = normalizeDimension(query.getDimension());
        MapSqlParameterSource params = new MapSqlParameterSource();
        String timePredicate = occurredAtPredicate(periodWindow, params);

        AdminUsageSummaryResponse.Overall overall = jdbcTemplate.queryForObject("""
                        SELECT
                            COALESCE(SUM(COALESCE(e.total_tokens, 0)), 0) AS total_recorded_tokens,
                            COALESCE(SUM(COALESCE(e.cached_tokens, 0)), 0) AS total_recorded_cached_tokens,
                            COALESCE(SUM(COALESCE(e.request_count, 0)), 0) AS total_request_count,
                            COALESCE(SUM(CASE WHEN ch.charge_status = 'CHARGEABLE' THEN COALESCE(ch.request_units, 0) ELSE 0 END), 0)
                                AS total_chargeable_request_count,
                            COALESCE(SUM(CASE WHEN ch.charge_status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_cost_event_count,
                            COALESCE(SUM(CASE WHEN cl.charge_status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_credit_event_count,
                            COALESCE(SUM(CASE WHEN ch.charge_status = 'CHARGEABLE' THEN COALESCE(ch.total_amount, 0) ELSE 0 END), 0)
                                AS total_billed_amount,
                            MAX(ch.currency) AS currency,
                            COALESCE(SUM(CASE WHEN cl.charge_status = 'CHARGEABLE' THEN COALESCE(cl.charged_credits_micros, 0) ELSE 0 END), 0)
                                AS total_charged_credits_micros
                        FROM llm_usage_event e
                        LEFT JOIN llm_usage_charge_ledger ch ON ch.usage_event_id = e.id
                        LEFT JOIN llm_usage_credit_ledger cl ON cl.usage_event_id = e.id
                        WHERE 1 = 1
                        """ + timePredicate,
                params,
                (rs, rowNum) -> AdminUsageSummaryResponse.Overall.builder()
                        .totalRecordedTokens(rs.getLong("total_recorded_tokens"))
                        .totalRecordedCachedTokens(rs.getLong("total_recorded_cached_tokens"))
                        .totalRequestCount(rs.getLong("total_request_count"))
                        .totalChargeableRequestCount(rs.getLong("total_chargeable_request_count"))
                        .pendingCostEventCount(rs.getLong("pending_cost_event_count"))
                        .pendingCreditEventCount(rs.getLong("pending_credit_event_count"))
                        .totalBilledAmount(creditFormattingService.formatAmount(rs.getBigDecimal("total_billed_amount")))
                        .currency(rs.getString("currency"))
                        .totalChargedCreditsMicros(rs.getLong("total_charged_credits_micros"))
                        .totalChargedCredits(creditFormattingService.formatCreditsMicros(rs.getLong("total_charged_credits_micros")))
                        .build());

        List<AdminUsageSummaryResponse.Row> rows = jdbcTemplate.query(summarySqlForDimension(dimension, timePredicate), params, (rs, rowNum) ->
                AdminUsageSummaryResponse.Row.builder()
                        .key(rs.getString("row_key"))
                        .label(rs.getString("label"))
                        .provider(rs.getString("provider"))
                        .modelCode(rs.getString("model_code"))
                        .usageFamily(rs.getString("usage_family"))
                        .purpose(rs.getString("purpose"))
                        .totalRecordedTokens(rs.getLong("total_recorded_tokens"))
                        .totalRecordedCachedTokens(rs.getLong("total_recorded_cached_tokens"))
                        .totalRequestCount(rs.getLong("total_request_count"))
                        .totalChargeableRequestCount(rs.getLong("total_chargeable_request_count"))
                        .pendingCostEventCount(rs.getLong("pending_cost_event_count"))
                        .pendingCreditEventCount(rs.getLong("pending_credit_event_count"))
                        .totalBilledAmount(creditFormattingService.formatAmount(rs.getBigDecimal("total_billed_amount")))
                        .currency(rs.getString("currency"))
                        .totalChargedCreditsMicros(rs.getLong("total_charged_credits_micros"))
                        .totalChargedCredits(creditFormattingService.formatCreditsMicros(rs.getLong("total_charged_credits_micros")))
                        .build());

        return AdminUsageSummaryResponse.builder()
                .dimension(dimension)
                .overall(overall)
                .rows(rows)
                .build();
    }

    private String usageEventBaseSql(
            AdminUsageEventsQuery query,
            PeriodWindow periodWindow,
            MapSqlParameterSource params
    ) {
        StringBuilder sql = new StringBuilder("""
                FROM llm_usage_event e
                JOIN users u ON u.id = e.user_id
                LEFT JOIN llm_provider provider_ref ON provider_ref.id = e.provider_id
                LEFT JOIN llm_model_catalog model_ref ON model_ref.id = e.model_id
                LEFT JOIN llm_provider model_provider ON model_provider.id = model_ref.provider_id
                LEFT JOIN llm_usage_charge_ledger ch ON ch.usage_event_id = e.id
                LEFT JOIN llm_usage_credit_ledger cl ON cl.usage_event_id = e.id
                WHERE 1 = 1
                """);
        sql.append(occurredAtPredicate(periodWindow, params));
        appendEqualsFilter(sql, params, "u.external_id", "userId", query.getUserId());
        appendEqualsFilter(sql, params, "e.purpose", "purpose", blankToNull(query.getPurpose()));
        appendEqualsFilter(sql, params, "e.usage_family", "usageFamily", blankToNull(query.getUsageFamily()));
        appendEqualsFilter(sql, params, "cl.charge_bucket", "chargeBucket", blankToNull(query.getChargeBucket()));
        appendEqualsFilter(sql, params, "cl.charge_status", "chargeStatus", blankToNull(query.getChargeStatus()));
        return sql.toString();
    }

    private MapSqlParameterSource baseUsageParams(AdminUsageEventsQuery query, PeriodWindow periodWindow) {
        return new MapSqlParameterSource();
    }

    private String summarySqlForDimension(String dimension, String timePredicate) {
        if ("purpose".equals(dimension)) {
            return """
                    SELECT
                        e.purpose || ':' || e.usage_family AS row_key,
                        e.purpose AS label,
                        NULL AS provider,
                        NULL AS model_code,
                        e.usage_family,
                        e.purpose,
                        COALESCE(SUM(COALESCE(e.total_tokens, 0)), 0) AS total_recorded_tokens,
                        COALESCE(SUM(COALESCE(e.cached_tokens, 0)), 0) AS total_recorded_cached_tokens,
                        COALESCE(SUM(COALESCE(e.request_count, 0)), 0) AS total_request_count,
                        COALESCE(SUM(CASE WHEN ch.charge_status = 'CHARGEABLE' THEN COALESCE(ch.request_units, 0) ELSE 0 END), 0)
                            AS total_chargeable_request_count,
                        COALESCE(SUM(CASE WHEN ch.charge_status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_cost_event_count,
                        COALESCE(SUM(CASE WHEN cl.charge_status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_credit_event_count,
                        COALESCE(SUM(CASE WHEN ch.charge_status = 'CHARGEABLE' THEN COALESCE(ch.total_amount, 0) ELSE 0 END), 0)
                            AS total_billed_amount,
                        MAX(ch.currency) AS currency,
                        COALESCE(SUM(CASE WHEN cl.charge_status = 'CHARGEABLE' THEN COALESCE(cl.charged_credits_micros, 0) ELSE 0 END), 0)
                            AS total_charged_credits_micros
                    FROM llm_usage_event e
                    LEFT JOIN llm_usage_charge_ledger ch ON ch.usage_event_id = e.id
                    LEFT JOIN llm_usage_credit_ledger cl ON cl.usage_event_id = e.id
                    WHERE 1 = 1
                    """ + timePredicate + """
                    GROUP BY e.purpose, e.usage_family
                    ORDER BY total_billed_amount DESC, row_key ASC
                    """;
        }
        return """
                SELECT
                    COALESCE(model_provider.provider_key, provider_ref.provider_key, e.provider)
                        || ':' || COALESCE(model_ref.model_code, e.model_code) || ':' || e.usage_family AS row_key,
                    COALESCE(model_ref.model_code, e.model_code) AS label,
                    COALESCE(model_provider.provider_key, provider_ref.provider_key, e.provider) AS provider,
                    COALESCE(model_ref.model_code, e.model_code) AS model_code,
                    e.usage_family,
                    NULL AS purpose,
                    COALESCE(SUM(COALESCE(e.total_tokens, 0)), 0) AS total_recorded_tokens,
                    COALESCE(SUM(COALESCE(e.cached_tokens, 0)), 0) AS total_recorded_cached_tokens,
                    COALESCE(SUM(COALESCE(e.request_count, 0)), 0) AS total_request_count,
                    COALESCE(SUM(CASE WHEN ch.charge_status = 'CHARGEABLE' THEN COALESCE(ch.request_units, 0) ELSE 0 END), 0)
                        AS total_chargeable_request_count,
                    COALESCE(SUM(CASE WHEN ch.charge_status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_cost_event_count,
                    COALESCE(SUM(CASE WHEN cl.charge_status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_credit_event_count,
                    COALESCE(SUM(CASE WHEN ch.charge_status = 'CHARGEABLE' THEN COALESCE(ch.total_amount, 0) ELSE 0 END), 0)
                        AS total_billed_amount,
                    MAX(ch.currency) AS currency,
                    COALESCE(SUM(CASE WHEN cl.charge_status = 'CHARGEABLE' THEN COALESCE(cl.charged_credits_micros, 0) ELSE 0 END), 0)
                        AS total_charged_credits_micros
                FROM llm_usage_event e
                LEFT JOIN llm_provider provider_ref ON provider_ref.id = e.provider_id
                LEFT JOIN llm_model_catalog model_ref ON model_ref.id = e.model_id
                LEFT JOIN llm_provider model_provider ON model_provider.id = model_ref.provider_id
                LEFT JOIN llm_usage_charge_ledger ch ON ch.usage_event_id = e.id
                LEFT JOIN llm_usage_credit_ledger cl ON cl.usage_event_id = e.id
                WHERE 1 = 1
                """ + timePredicate + """
                GROUP BY COALESCE(model_provider.provider_key, provider_ref.provider_key, e.provider),
                         COALESCE(model_ref.model_code, e.model_code),
                         e.usage_family
                ORDER BY total_billed_amount DESC, row_key ASC
                """;
    }

    private String normalizeDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return "modelCode";
        }
        if ("modelCode".equals(dimension) || "purpose".equals(dimension)) {
            return dimension;
        }
        throw new BusinessException("VALIDATION_ERROR", "Unsupported usage summary dimension");
    }

    private PeriodWindow resolveWindow(OffsetDateTime from, OffsetDateTime to) {
        if (from == null && to == null) {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            LocalDateTime periodStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
            return new PeriodWindow(periodStart, periodStart.plusMonths(1));
        }
        return new PeriodWindow(toUtcLocalDateTime(from), toUtcLocalDateTime(to));
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private LocalDateTime toUtcLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private String occurredAtPredicate(PeriodWindow periodWindow, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder();
        if (periodWindow.periodStart() != null) {
            sql.append("  AND e.occurred_at >= :from\n");
            params.addValue("from", periodWindow.periodStart());
        }
        if (periodWindow.periodEnd() != null) {
            sql.append("  AND e.occurred_at < :to\n");
            params.addValue("to", periodWindow.periodEnd());
        }
        return sql.toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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

    private String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
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

    private record PeriodWindow(LocalDateTime periodStart, LocalDateTime periodEnd) {
    }
}
