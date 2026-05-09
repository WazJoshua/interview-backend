package com.josh.interviewj.admin.service;

import com.josh.interviewj.admin.model.AdminOperationActionType;
import com.josh.interviewj.admin.model.AdminOperationLog;
import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.repository.AdminOperationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOperationLogServiceTest {

    @Mock
    private AdminOperationLogRepository adminOperationLogRepository;

    private AdminOperationLogService service;

    @BeforeEach
    void setUp() {
        service = new AdminOperationLogService(JsonMapper.builder().build(), adminOperationLogRepository);
    }

    @Test
    void recordCreate_SerializesSnapshotsAsJsonAndAllowsNullRequestId() {
        when(adminOperationLogRepository.save(any(AdminOperationLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordCreate(
                100L,
                AdminOperationResourceType.LLM_MODEL_CATALOG,
                "42",
                null,
                new Snapshot("after"),
                Map.of("source", "admin-ui")
        );

        ArgumentCaptor<AdminOperationLog> captor = ArgumentCaptor.forClass(AdminOperationLog.class);
        verify(adminOperationLogRepository).save(captor.capture());
        AdminOperationLog saved = captor.getValue();
        assertThat(saved.getActorUserId()).isEqualTo(100L);
        assertThat(saved.getActionType()).isEqualTo(AdminOperationActionType.CREATE);
        assertThat(saved.getResourceType()).isEqualTo(AdminOperationResourceType.LLM_MODEL_CATALOG);
        assertThat(saved.getResourceId()).isEqualTo("42");
        assertThat(saved.getRequestId()).isNull();
        assertThat(saved.getBeforeSnapshot()).isNull();
        assertThat(saved.getAfterSnapshot()).isEqualTo("{\"value\":\"after\"}");
        assertThat(saved.getMetadata()).isEqualTo("{\"source\":\"admin-ui\"}");
    }

    @Test
    void recordUpdate_UsesFixedFirstVersionEnums() {
        assertThat(AdminOperationActionType.values())
                .containsExactly(
                        AdminOperationActionType.CREATE,
                        AdminOperationActionType.UPDATE
                );
        assertThat(AdminOperationResourceType.values())
                .containsExactly(
                        AdminOperationResourceType.CREDIT_POLICY_VERSION,
                        AdminOperationResourceType.USER_CREDIT_POLICY,
                        AdminOperationResourceType.LLM_PROVIDER,
                        AdminOperationResourceType.LLM_MODEL_CATALOG,
                        AdminOperationResourceType.LLM_PRICING_VERSION,
                        AdminOperationResourceType.LLM_ROUTING_POLICY,
                        AdminOperationResourceType.USER_ROLE_FLAGS,
                        AdminOperationResourceType.BILLING_PLAN,
                        AdminOperationResourceType.BILLING_PLAN_VERSION,
                        AdminOperationResourceType.CREDIT_PURCHASE_SKU,
                        AdminOperationResourceType.CREDIT_PURCHASE_SKU_VERSION,
                        AdminOperationResourceType.SUBSCRIPTION_CONTRACT,
                        AdminOperationResourceType.BILLING_RECONCILIATION_CASE,
                        AdminOperationResourceType.BILLING_MANUAL_ADJUSTMENT,
                        AdminOperationResourceType.PAYMENT_ORDER,
                        AdminOperationResourceType.SYSTEM_SETTING,
                        AdminOperationResourceType.LLM_PROMPT_TEMPLATE
                );
    }

    @Test
    void recordUpdate_SerializesBeforeAndAfterSnapshotsInsteadOfToString() {
        when(adminOperationLogRepository.save(any(AdminOperationLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordUpdate(
                101L,
                AdminOperationResourceType.USER_ROLE_FLAGS,
                "user-1",
                "req_123",
                new Snapshot("before"),
                new Snapshot("after"),
                Map.of("field", "inviter")
        );

        ArgumentCaptor<AdminOperationLog> captor = ArgumentCaptor.forClass(AdminOperationLog.class);
        verify(adminOperationLogRepository).save(captor.capture());
        AdminOperationLog saved = captor.getValue();
        assertThat(saved.getActionType()).isEqualTo(AdminOperationActionType.UPDATE);
        assertThat(saved.getBeforeSnapshot()).isEqualTo("{\"value\":\"before\"}");
        assertThat(saved.getAfterSnapshot()).isEqualTo("{\"value\":\"after\"}");
        assertThat(saved.getBeforeSnapshot()).doesNotContain("snapshot-before");
        assertThat(saved.getAfterSnapshot()).doesNotContain("snapshot-after");
    }

    private record Snapshot(String value) {
        @Override
        public String toString() {
            return "snapshot-" + value;
        }
    }
}
