package com.josh.interviewj.usage.service;

import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.service.LegacyCreditPolicyProjectionService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.usage.dto.request.AdminUsageEventsQuery;
import com.josh.interviewj.usage.dto.request.CreateCreditPolicyVersionRequest;
import com.josh.interviewj.usage.dto.request.UpdateUserCreditPolicyRequest;
import com.josh.interviewj.usage.dto.response.AdminUserCreditPolicyResponse;
import com.josh.interviewj.usage.dto.response.AdminCreditPolicyVersionResponse;
import com.josh.interviewj.usage.model.BillingUnit;
import com.josh.interviewj.usage.model.ChargeBucket;
import com.josh.interviewj.usage.model.UsageCreditPolicyVersion;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.model.UserCreditPolicy;
import com.josh.interviewj.usage.repository.UsageCreditPolicyVersionRepository;
import com.josh.interviewj.usage.repository.UserCreditPeriodRepository;
import com.josh.interviewj.usage.repository.UserCreditPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.eq;

@ExtendWith(MockitoExtension.class)
class AdminCreditsBillingServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UsageCreditPolicyVersionRepository usageCreditPolicyVersionRepository;

    @Mock
    private UserCreditPolicyRepository userCreditPolicyRepository;

    @Mock
    private UserCreditPeriodRepository userCreditPeriodRepository;

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Mock
    private AdminOperationLogService adminOperationLogService;

    @Mock
    private LegacyCreditPolicyProjectionService legacyCreditPolicyProjectionService;

    private AdminCreditsBillingService service;

    @BeforeEach
    void setUp() {
        service = new AdminCreditsBillingService(
                Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC),
                JsonMapper.builder().build(),
                userRepository,
                usageCreditPolicyVersionRepository,
                userCreditPolicyRepository,
                userCreditPeriodRepository,
                jdbcTemplate,
                new CreditFormattingService(),
                adminOperationLogService,
                legacyCreditPolicyProjectionService
        );
    }

    @Test
    void createPolicyVersion_WhenBillingUnitRatioCombinationInvalid_ThrowsAdminCredit001() {
        CreateCreditPolicyVersionRequest request = new CreateCreditPolicyVersionRequest();
        request.setPurpose("kb_query_embedding");
        request.setChargeBucket("KB_QUERY_CREDITS");
        request.setUsageFamily("EMBEDDING");
        request.setEffectiveFrom(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        request.setBillingUnit("TOKEN_AND_REQUEST");
        request.setRequestRatio(new BigDecimal("0.250"));

        assertThatThrownBy(() -> service.createPolicyVersion(100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("ADMIN_CREDIT_001");
    }

    @Test
    void createPolicyVersion_WhenBillingUnitUnsupported_ThrowsValidationError() {
        CreateCreditPolicyVersionRequest request = validPolicyVersionRequest();
        request.setBillingUnit("TOKEN_PER_SECOND");

        assertThatThrownBy(() -> service.createPolicyVersion(100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createPolicyVersion_WhenChargeBucketUnsupported_ThrowsValidationError() {
        CreateCreditPolicyVersionRequest request = validPolicyVersionRequest();
        request.setChargeBucket("PROFILE_CREDITS");

        assertThatThrownBy(() -> service.createPolicyVersion(100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createPolicyVersion_WhenUsageFamilyUnsupported_ThrowsValidationError() {
        CreateCreditPolicyVersionRequest request = validPolicyVersionRequest();
        request.setUsageFamily("SPEECH");

        assertThatThrownBy(() -> service.createPolicyVersion(100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createPolicyVersion_WhenOnlyOpenEndedPredecessorOverlaps_TruncatesAndCreatesFutureVersion() {
        CreateCreditPolicyVersionRequest request = new CreateCreditPolicyVersionRequest();
        request.setPurpose("analysis");
        request.setChargeBucket("RESUME_CREDITS");
        request.setUsageFamily("CHAT");
        request.setEffectiveFrom(OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        request.setBillingUnit("TOKEN");
        request.setPromptTokenRatio(new BigDecimal("0.100"));

        UsageCreditPolicyVersion existing = UsageCreditPolicyVersion.builder()
                .id(9L)
                .purpose("analysis")
                .chargeBucket(ChargeBucket.RESUME_CREDITS)
                .usageFamily(UsageFamily.CHAT)
                .effectiveFrom(LocalDateTime.of(2026, 3, 1, 0, 0))
                .effectiveTo(null)
                .billingUnit(BillingUnit.TOKEN)
                .promptTokenRatio(new BigDecimal("0.080"))
                .build();
        when(usageCreditPolicyVersionRepository.findOverlappingVersions(
                any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(existing));
        when(usageCreditPolicyVersionRepository.save(any(UsageCreditPolicyVersion.class)))
                .thenAnswer(invocation -> {
                    UsageCreditPolicyVersion version = invocation.getArgument(0);
                    if (version.getId() == null) {
                        version.setId(10L);
                    }
                    return version;
                });

        AdminCreditPolicyVersionResponse response = service.createPolicyVersion(100L, request);

        ArgumentCaptor<UsageCreditPolicyVersion> captor = ArgumentCaptor.forClass(UsageCreditPolicyVersion.class);
        verify(usageCreditPolicyVersionRepository, times(2)).save(captor.capture());
        List<UsageCreditPolicyVersion> savedVersions = captor.getAllValues();
        assertThat(savedVersions.get(0).getId()).isEqualTo(9L);
        assertThat(savedVersions.get(0).getEffectiveTo()).isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0));
        assertThat(savedVersions.get(1).getId()).isEqualTo(10L);
        assertThat(savedVersions.get(1).getEffectiveFrom()).isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0));
        assertThat(savedVersions.get(1).getEffectiveTo()).isNull();
        assertThat(savedVersions.get(1).getPromptTokenRatio()).isEqualByComparingTo("0.100");
        assertThat(response.getId()).isEqualTo("10");
        assertThat(response.getEffectiveFrom()).isEqualTo(OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        verify(adminOperationLogService).recordUpdate(
                eq(100L),
                eq(AdminOperationResourceType.CREDIT_POLICY_VERSION),
                eq("9"),
                eq(null),
                any(),
                any(),
                eq(Map.of(
                        "purpose", "analysis",
                        "chargeBucket", "RESUME_CREDITS",
                        "usageFamily", "CHAT",
                        "changeType", "TRUNCATED_BY_SUCCESSOR_CREATE",
                        "successorEffectiveFrom", "2026-05-01T00:00"
                ))
        );
        verify(adminOperationLogService).recordCreate(
                eq(100L),
                eq(AdminOperationResourceType.CREDIT_POLICY_VERSION),
                eq("10"),
                eq(null),
                any(),
                eq(Map.of(
                        "purpose", "analysis",
                        "chargeBucket", "RESUME_CREDITS",
                        "usageFamily", "CHAT"
                ))
        );
    }

    @Test
    void createPolicyVersion_WhenReplacingCurrentOpenEndedVersion_ThrowsAdminCredit002() {
        CreateCreditPolicyVersionRequest request = new CreateCreditPolicyVersionRequest();
        request.setPurpose("analysis");
        request.setChargeBucket("RESUME_CREDITS");
        request.setUsageFamily("CHAT");
        request.setEffectiveFrom(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        request.setBillingUnit("TOKEN");
        request.setPromptTokenRatio(new BigDecimal("0.100"));

        when(usageCreditPolicyVersionRepository.findOverlappingVersions(
                any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(UsageCreditPolicyVersion.builder()
                        .id(9L)
                        .purpose("analysis")
                        .chargeBucket(ChargeBucket.RESUME_CREDITS)
                        .usageFamily(UsageFamily.CHAT)
                        .effectiveFrom(LocalDateTime.of(2026, 3, 1, 0, 0))
                        .effectiveTo(null)
                        .billingUnit(BillingUnit.TOKEN)
                        .promptTokenRatio(new BigDecimal("0.080"))
                        .build()));

        assertThatThrownBy(() -> service.createPolicyVersion(100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("ADMIN_CREDIT_002");
        verify(usageCreditPolicyVersionRepository, never()).save(any(UsageCreditPolicyVersion.class));
    }

    @Test
    void getUserCreditPolicy_WhenMissing_ThrowsAdminCredit003() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByExternalId(userId)).thenReturn(Optional.of(user(userId)));
        when(userCreditPolicyRepository.findByUserIdOrderByEffectiveFromAsc(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getUserCreditPolicy(userId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("ADMIN_CREDIT_003");
    }

    @Test
    void getCreditLedger_WhenOnlyToProvided_DoesNotAddLowerBoundPredicate() {
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        AdminUsageEventsQuery query = new AdminUsageEventsQuery();
        query.setTo(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        service.getCreditLedger(query);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), paramsCaptor.capture(), eq(Long.class));
        assertThat(sqlCaptor.getValue()).doesNotContain("e.occurred_at >= :from");
        assertThat(sqlCaptor.getValue()).contains("e.occurred_at < :to");
        assertThat(paramsCaptor.getValue().getValues()).doesNotContainKey("from");
        assertThat(paramsCaptor.getValue().getValues()).containsKey("to");
    }

    @Test
    void updateUserCreditPolicy_WhenOnlyOpenEndedPredecessorOverlaps_TruncatesAndCreatesPendingPolicy() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByExternalId(userId)).thenReturn(Optional.of(user(userId)));
        UserCreditPolicy existing = UserCreditPolicy.builder()
                .id(11L)
                .userId(1L)
                .effectiveFrom(LocalDateTime.of(2026, 3, 1, 0, 0))
                .effectiveTo(null)
                .resumeCreditsLimitMicros(100L)
                .kbQueryCreditsLimitMicros(100L)
                .kbIngestionCreditsLimitMicros(100L)
                .interviewCreditsLimitMicros(100L)
                .build();
        when(userCreditPolicyRepository.findOverlappingPolicies(anyLong(), any(), any(), any()))
                .thenReturn(List.of(existing));

        List<UserCreditPolicy> policies = new ArrayList<>();
        policies.add(existing);
        when(userCreditPolicyRepository.findByUserIdOrderByEffectiveFromAsc(1L)).thenAnswer(invocation -> policies);
        when(userCreditPolicyRepository.save(any(UserCreditPolicy.class)))
                .thenAnswer(invocation -> {
                    UserCreditPolicy policy = invocation.getArgument(0);
                    if (policy.getId() == null) {
                        policy.setId(12L);
                        policies.add(policy);
                    }
                    return policy;
                });

        UpdateUserCreditPolicyRequest request = new UpdateUserCreditPolicyRequest();
        request.setEffectiveFrom(OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        request.setResumeCreditsLimitMicros(240L);
        request.setKbQueryCreditsLimitMicros(320L);
        request.setKbIngestionCreditsLimitMicros(500L);
        request.setInterviewCreditsLimitMicros(260L);

        AdminUserCreditPolicyResponse response = service.updateUserCreditPolicy(100L, userId, request);

        ArgumentCaptor<UserCreditPolicy> captor = ArgumentCaptor.forClass(UserCreditPolicy.class);
        verify(userCreditPolicyRepository, times(2)).save(captor.capture());
        List<UserCreditPolicy> savedPolicies = captor.getAllValues();
        assertThat(savedPolicies.get(0).getId()).isEqualTo(11L);
        assertThat(savedPolicies.get(0).getEffectiveTo()).isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0));
        assertThat(savedPolicies.get(1).getId()).isEqualTo(12L);
        assertThat(savedPolicies.get(1).getEffectiveFrom()).isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0));
        assertThat(response.getActivePolicy()).isNotNull();
        assertThat(response.getActivePolicy().getId()).isEqualTo("11");
        assertThat(response.getPendingPolicy()).isNotNull();
        assertThat(response.getPendingPolicy().getId()).isEqualTo("12");
        verify(adminOperationLogService).recordUpdate(
                eq(100L),
                eq(AdminOperationResourceType.USER_CREDIT_POLICY),
                eq(userId.toString()),
                eq(null),
                any(),
                any(),
                eq(Map.of("targetUserId", userId.toString()))
        );
    }

    private CreateCreditPolicyVersionRequest validPolicyVersionRequest() {
        CreateCreditPolicyVersionRequest request = new CreateCreditPolicyVersionRequest();
        request.setPurpose("kb_query_embedding");
        request.setChargeBucket("KB_QUERY_CREDITS");
        request.setUsageFamily("EMBEDDING");
        request.setEffectiveFrom(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        request.setBillingUnit("TOKEN_AND_REQUEST");
        request.setPromptTokenRatio(new BigDecimal("0.001"));
        request.setRequestRatio(new BigDecimal("0.250"));
        return request;
    }

    private User user(UUID userId) {
        return User.builder()
                .id(1L)
                .externalId(userId)
                .username("admin")
                .email("admin@example.com")
                .password("hashed")
                .build();
    }
}
