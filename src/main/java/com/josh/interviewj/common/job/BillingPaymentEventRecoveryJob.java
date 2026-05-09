package com.josh.interviewj.common.job;

import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.model.PaymentEventProcessStatus;
import com.josh.interviewj.billing.repository.PaymentEventRepository;
import com.josh.interviewj.billing.service.PaymentEventApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BillingPaymentEventRecoveryJob {

    private final BillingProperties billingProperties;
    private final PaymentEventRepository paymentEventRepository;
    private final PaymentEventApplicationService paymentEventApplicationService;

    @Scheduled(fixedDelayString = "#{@billingProperties.paymentEventRecovery.pollInterval.toMillis()}")
    public void recoverFailedEvents() {
        if (!billingProperties.getPaymentEventRecovery().isEnabled()) {
            return;
        }
        paymentEventRepository.findByProcessStatusInOrderByLastAttemptAtAscIdAsc(
                        List.of(PaymentEventProcessStatus.FAILED_RETRYABLE),
                        PageRequest.of(0, billingProperties.getReconciliation().getScanBatchSize())
                )
                .forEach(event -> {
                    try {
                        paymentEventApplicationService.retryFailedEvent(event.getId());
                    } catch (RuntimeException exception) {
                        log.warn("billing_payment_event_recovery_failed eventId={}, message={}", event.getId(), exception.getMessage());
                    }
                });
    }
}
