package com.josh.interviewj.usage.service;

import com.josh.interviewj.llm.core.ProviderUsage;
import com.josh.interviewj.usage.model.UsageFamily;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UsageRecordingFacadeTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void record_WhenTransactionSynchronizationActive_PublishesAfterCommitEvent() {
        TransactionalUsageSettlementService settlementService = mock(TransactionalUsageSettlementService.class);
        UsageRecordingFacade facade = new UsageRecordingFacade(settlementService);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        UsageOperationContext context = successContext();

        facade.record(context);

        verify(settlementService).record(context);
        verify(settlementService, never()).recordInNewTransaction(any());
    }

    @Test
    void record_WhenNoTransactionSynchronization_RecordsImmediatelyInIndependentTransaction() {
        TransactionalUsageSettlementService settlementService = mock(TransactionalUsageSettlementService.class);
        UsageRecordingFacade facade = new UsageRecordingFacade(settlementService);

        UsageOperationContext context = successContext();

        facade.record(context);

        verify(settlementService).recordInNewTransaction(context);
    }

    @Test
    void listener_DelegatesToTransactionalSettlementService() {
        TransactionalUsageSettlementService settlementService = mock(TransactionalUsageSettlementService.class);
        UsageSuccessRecordingListener listener = new UsageSuccessRecordingListener(settlementService);
        UsageSuccessRecordedEvent event = new UsageSuccessRecordedEvent(successContext());

        listener.onUsageSuccessRecorded(event);

        verify(settlementService).recordInNewTransaction(event.context());
    }

    private UsageOperationContext successContext() {
        return new UsageOperationContext(
                "analysis",
                "default",
                "qwen-plus",
                new ProviderUsage(UsageFamily.CHAT, 1L, 100L, 40L, 140L, 10L),
                "RESUME_ANALYSIS_REPORT",
                "report-ext-1",
                "op-1",
                101L,
                UsageBusinessOutcome.SUCCESS,
                null
        );
    }
}
