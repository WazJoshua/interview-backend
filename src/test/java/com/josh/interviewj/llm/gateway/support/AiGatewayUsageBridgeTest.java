package com.josh.interviewj.llm.gateway.support;

import com.josh.interviewj.llm.core.LlmResponse;
import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.llm.gateway.dto.AiInvocationContext;
import com.josh.interviewj.llm.gateway.dto.AiInvocationResult;
import com.josh.interviewj.llm.gateway.dto.BusinessOperationContext;
import com.josh.interviewj.llm.gateway.dto.ExecutionDisposition;
import com.josh.interviewj.llm.gateway.dto.InvocationUsageOutcome;
import com.josh.interviewj.usage.model.UsageFamily;
import com.josh.interviewj.usage.service.TransactionalUsageSettlementService;
import com.josh.interviewj.usage.service.UsageBusinessOutcome;
import com.josh.interviewj.usage.service.UsageContextFactory;
import com.josh.interviewj.usage.service.UsageOperationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGatewayUsageBridgeTest {

    @Mock
    private UsageContextFactory usageContextFactory;

    @Mock
    private TransactionalUsageSettlementService transactionalUsageSettlementService;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void recordInvocationOutcome_WhenNoTransaction_UsesIndependentTransactionForSettlement() {
        AiGatewayUsageBridge bridge = new AiGatewayUsageBridge(usageContextFactory, transactionalUsageSettlementService);
        UsageOperationContext usageContext = usageContext(UsageBusinessOutcome.SUCCESS, null);
        when(usageContextFactory.failureFromLlm(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any(), any()))
                .thenReturn(usageContext);

        bridge.recordInvocationOutcome(
                operationContext(),
                invocationContext(),
                AiInvocationResult.fromChat(chatResponse()),
                ExecutionDisposition.EXECUTED,
                InvocationUsageOutcome.SUCCESS,
                null
        );

        verify(transactionalUsageSettlementService).recordInNewTransaction(any());
        verify(transactionalUsageSettlementService, never()).record(any());
    }

    @Test
    void recordInvocationOutcome_WhenFailureInsideTransaction_UsesIndependentTransactionForSettlement() {
        AiGatewayUsageBridge bridge = new AiGatewayUsageBridge(usageContextFactory, transactionalUsageSettlementService);
        UsageOperationContext usageContext = usageContext(UsageBusinessOutcome.FAILED_NON_CHARGEABLE, "parse failed");
        when(usageContextFactory.failureFromLlm(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any(), any()))
                .thenReturn(usageContext);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        bridge.recordInvocationOutcome(
                operationContext(),
                invocationContext(),
                AiInvocationResult.fromChat(chatResponse()),
                ExecutionDisposition.EXECUTED,
                InvocationUsageOutcome.FAILED_NON_CHARGEABLE,
                "parse failed"
        );

        verify(transactionalUsageSettlementService).recordInNewTransaction(any());
        verify(transactionalUsageSettlementService, never()).record(any());
    }

    @Test
    void recordInvocationOutcome_WhenSuccessInsideTransaction_UsesIndependentTransactionForSettlement() {
        AiGatewayUsageBridge bridge = new AiGatewayUsageBridge(usageContextFactory, transactionalUsageSettlementService);
        UsageOperationContext usageContext = usageContext(UsageBusinessOutcome.SUCCESS, null);
        when(usageContextFactory.failureFromLlm(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any(), any()))
                .thenReturn(usageContext);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        bridge.recordInvocationOutcome(
                operationContext(),
                invocationContext(),
                AiInvocationResult.fromChat(chatResponse()),
                ExecutionDisposition.EXECUTED,
                InvocationUsageOutcome.SUCCESS,
                null
        );

        verify(transactionalUsageSettlementService).recordInNewTransaction(any());
        verify(transactionalUsageSettlementService, never()).record(any());
    }

    private BusinessOperationContext operationContext() {
        return new BusinessOperationContext(
                "biz-1",
                101L,
                "RESUME",
                "resume-1",
                "parse",
                List.of("RESUME_CREDITS"),
                Map.of()
        );
    }

    private AiInvocationContext invocationContext() {
        return new AiInvocationContext(
                "inv-1",
                "parse",
                UsageFamily.CHAT,
                "RESUME_CREDITS",
                false,
                Map.of()
        );
    }

    private LlmResponse chatResponse() {
        return new LlmResponse(
                "{\"ok\":true}",
                "dispatcher_rc",
                "gpt-5.4",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L)
        );
    }

    private UsageOperationContext usageContext(UsageBusinessOutcome outcome, String failureReason) {
        return new UsageOperationContext(
                "parse",
                "dispatcher_rc",
                "gpt-5.4",
                new ProviderUsage(UsageFamily.CHAT, 1L, 10L, 5L, 15L, 0L),
                "RESUME",
                "resume-1",
                "inv-1",
                "biz-1",
                101L,
                outcome,
                failureReason,
                "EXECUTED",
                null,
                null,
                Map.of()
        );
    }
}
