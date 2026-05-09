package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.PaymentEvent;
import com.josh.interviewj.billing.model.PaymentEventProcessStatus;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.provider.VerifiedPaymentNotification;
import com.josh.interviewj.billing.repository.PaymentEventRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventApplicationService {

    private static final List<PaymentEventProcessStatus> CLAIMABLE_STATUSES = List.of(
            PaymentEventProcessStatus.VERIFIED,
            PaymentEventProcessStatus.FAILED_RETRYABLE
    );
    private static final Set<String> SUCCESS_EVENT_TYPES = Set.of(
            "PAID",
            "PAYMENT_COMPLETED",
            "PAYMENT_PAID",
            "PAYMENT_SUCCESS",
            "PAYMENT_SUCCEEDED",
            "SUCCESS",
            "SUCCEEDED"
    );
    private static final Set<String> PENDING_EVENT_TYPES = Set.of(
            "PENDING",
            "PAYMENT_PENDING",
            "WAIT_BUYER_PAY",
            "AWAITING_CONFIRMATION"
    );

    private final Clock clock;
    private final BillingSnapshotCodec billingSnapshotCodec;
    private final BillingEventService billingEventService;
    private final BillingReconciliationService billingReconciliationService;
    private final BillingRefundApplicationService billingRefundApplicationService;
    private final CreditBalanceProjectionService creditBalanceProjectionService;
    private final SubscriptionLifecycleService subscriptionLifecycleService;
    private final PaymentEventRepository paymentEventRepository;
    private final PaymentOrderRepository paymentOrderRepository;

    /**
     * Result of applying an order.
     */
    private enum OrderApplicationResult {
        /**
         * Order fulfilled successfully.
         */
        FULFILLED,
        /**
         * Order requires reconciliation (e.g., post-cutover missing reservation).
         */
        REQUIRES_RECONCILIATION
    }

    @Transactional
    public void applyVerifiedEvent(Long paymentEventId, VerifiedPaymentNotification notification) {
        PaymentEvent event = paymentEventRepository.findById(paymentEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment event not found"));
        if (event.getProcessStatus() == PaymentEventProcessStatus.APPLIED
                || event.getProcessStatus() == PaymentEventProcessStatus.APPLYING
                || event.getProcessStatus() == PaymentEventProcessStatus.FAILED_TERMINAL) {
            return;
        }
        if (event.getProcessStatus() == PaymentEventProcessStatus.RECEIVED) {
            event.setProcessStatus(PaymentEventProcessStatus.VERIFIED);
            event = paymentEventRepository.save(event);
        }
        int claimed = paymentEventRepository.claimForApplying(
                event.getId(),
                CLAIMABLE_STATUSES,
                PaymentEventProcessStatus.APPLYING,
                nowUtc()
        );
        if (claimed == 0) {
            return;
        }

        PaymentEvent applyingEvent = paymentEventRepository.findById(paymentEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment event not found after claim"));
        try {
            PaymentOrder order = resolveOrder(applyingEvent, notification);
            PaymentEventSemantic semantic = classifyEvent(notification.eventType());
            if (semantic == PaymentEventSemantic.REVERSAL) {
                billingRefundApplicationService.handleProviderReversal(order, applyingEvent, notification);
                markFailed(
                        applyingEvent,
                        PaymentEventProcessStatus.FAILED_RETRYABLE,
                        ErrorCode.PAYMENT_004,
                        "Reversal payment event requires review"
                );
                return;
            }
            if (semantic == PaymentEventSemantic.PENDING) {
                if (order.getStatus() == PaymentOrderStatus.CREATED || order.getStatus() == PaymentOrderStatus.PENDING_PROVIDER) {
                    order.setStatus(PaymentOrderStatus.AWAITING_CONFIRMATION);
                    paymentOrderRepository.save(order);
                }
                markFailed(
                        applyingEvent,
                        PaymentEventProcessStatus.FAILED_RETRYABLE,
                        ErrorCode.PAYMENT_004,
                        "Payment event is pending provider confirmation"
                );
                return;
            }
            if (semantic != PaymentEventSemantic.SUCCESS) {
                markFailed(
                        applyingEvent,
                        PaymentEventProcessStatus.FAILED_RETRYABLE,
                        ErrorCode.PAYMENT_004,
                        "Payment event is not in a successful terminal state"
                );
                return;
            }
            validateOrderAmount(order, notification, applyingEvent);
            if (order.getExpiresAt().isBefore(notification.occurredAt()) || order.getStatus() == PaymentOrderStatus.EXPIRED) {
                order.setStatus(PaymentOrderStatus.REQUIRES_RECONCILIATION);
                paymentOrderRepository.save(order);
                billingReconciliationService.createCase(
                        order.getUserId(),
                        order.getId(),
                        applyingEvent.getId(),
                        order.getSubscriptionContractId(),
                        "PAYMENT_ORDER",
                        "LATE_SUCCESS_AFTER_EXPIRY",
                        Map.of("orderNo", order.getOrderNo(), "occurredAt", notification.occurredAt().toString())
                );
                markApplied(applyingEvent);
                return;
            }
            if (order.getStatus() == PaymentOrderStatus.CANCELED || order.getStatus() == PaymentOrderStatus.FAILED) {
                PaymentOrderStatus previousStatus = order.getStatus();
                String caseCode = previousStatus == PaymentOrderStatus.CANCELED
                        ? "LATE_SUCCESS_AFTER_CANCEL" : "LATE_SUCCESS_AFTER_FAILURE";
                order.setStatus(PaymentOrderStatus.REQUIRES_RECONCILIATION);
                paymentOrderRepository.save(order);
                billingReconciliationService.createCase(
                        order.getUserId(),
                        order.getId(),
                        applyingEvent.getId(),
                        order.getSubscriptionContractId(),
                        "PAYMENT_ORDER",
                        caseCode,
                        Map.of("orderNo", order.getOrderNo(), "previousStatus", previousStatus.name(),
                                "occurredAt", notification.occurredAt().toString())
                );
                markApplied(applyingEvent);
                return;
            }
            OrderApplicationResult result = applyOrder(order, notification, applyingEvent);
            if (result == OrderApplicationResult.REQUIRES_RECONCILIATION) {
                // Order requires reconciliation (e.g., post-cutover missing reservation)
                // The reconciliation event is already recorded by InventoryReservationService
                // Create reconciliation case and mark order appropriately
                order.setStatus(PaymentOrderStatus.REQUIRES_RECONCILIATION);
                paymentOrderRepository.save(order);
                billingReconciliationService.createCase(
                        order.getUserId(),
                        order.getId(),
                        applyingEvent.getId(),
                        order.getSubscriptionContractId(),
                        "INVENTORY_RECONCILIATION",
                        "POST_CUTOVER_ORDER_WITHOUT_RESERVATION",
                        Map.of("orderNo", order.getOrderNo())
                );
                markApplied(applyingEvent);
                return;
            }
            order.setStatus(PaymentOrderStatus.SUCCEEDED);
            order.setPaidAt(notification.occurredAt());
            paymentOrderRepository.save(order);
            markApplied(applyingEvent);
        } catch (BusinessException exception) {
            log.warn("payment_event_apply_terminal_failed eventId={}, code={}, message={}",
                    applyingEvent.getId(), exception.getErrorCode(), exception.getMessage());
            markFailed(applyingEvent, PaymentEventProcessStatus.FAILED_TERMINAL, exception.getErrorCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("payment_event_apply_retryable_failed eventId={}, message={}",
                    applyingEvent.getId(), exception.getMessage());
            markFailed(applyingEvent, PaymentEventProcessStatus.FAILED_RETRYABLE, "RETRYABLE", safeMessage(exception));
            throw exception;
        }
    }

    @Transactional
    public void retryFailedEvent(Long paymentEventId) {
        PaymentEvent paymentEvent = paymentEventRepository.findById(paymentEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment event not found"));
        PaymentOrder order = paymentEvent.getPaymentOrderId() == null
                ? null
                : paymentOrderRepository.findById(paymentEvent.getPaymentOrderId()).orElse(null);
        if (order == null) {
            markFailed(paymentEvent, PaymentEventProcessStatus.FAILED_TERMINAL, ErrorCode.PAYMENT_002, "Payment order not found for retry");
            return;
        }
        applyVerifiedEvent(paymentEventId, new VerifiedPaymentNotification(
                paymentEvent.getProvider(),
                paymentEvent.getProviderEventId(),
                order.getProviderOrderRef(),
                null,
                paymentEvent.getEventType(),
                order.getAmount(),
                order.getCurrency(),
                paymentEvent.getOccurredAt(),
                paymentEvent.getRawPayload(),
                Map.of("recovered", true)
        ));
    }

    private PaymentOrder resolveOrder(PaymentEvent paymentEvent, VerifiedPaymentNotification notification) {
        if (paymentEvent.getPaymentOrderId() != null) {
            return paymentOrderRepository.findById(paymentEvent.getPaymentOrderId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found"));
        }
        PaymentOrder order = paymentOrderRepository.findByProviderAndProviderOrderRef(notification.provider(), notification.providerOrderRef())
                .orElseGet(() -> paymentOrderRepository.findByOrderNo(notification.providerOrderRef()).orElse(null));
        if (order == null) {
            throw new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found");
        }
        paymentEvent.setPaymentOrderId(order.getId());
        paymentEventRepository.save(paymentEvent);
        return order;
    }

    private void validateOrderAmount(PaymentOrder order, VerifiedPaymentNotification notification, PaymentEvent paymentEvent) {
        if (notification.amount() == null
                || notification.currency() == null
                || order.getAmount().compareTo(notification.amount()) != 0
                || !order.getCurrency().equalsIgnoreCase(notification.currency())) {
            order.setStatus(PaymentOrderStatus.REQUIRES_RECONCILIATION);
            paymentOrderRepository.save(order);
            billingReconciliationService.createCase(
                    order.getUserId(),
                    order.getId(),
                    paymentEvent.getId(),
                    order.getSubscriptionContractId(),
                    "PAYMENT_ORDER",
                    "AMOUNT_MISMATCH",
                    Map.of(
                            "orderNo", order.getOrderNo(),
                            "expectedAmount", order.getAmount().toPlainString(),
                            "actualAmount", notification.amount() == null ? null : notification.amount().toPlainString(),
                            "expectedCurrency", order.getCurrency(),
                            "actualCurrency", notification.currency()
                    )
            );
            throw new BusinessException(ErrorCode.PAYMENT_005, "Payment amount or currency does not match frozen order snapshot");
        }
    }

    /**
     * Apply order fulfillment after successful payment.
     *
     * IMPORTANT: For subscription orders, inventory confirmation is performed BEFORE
     * any fulfillment side effects to avoid partial commits when post-cutover order
     * lacks reservation.
     *
     * @return FULFILLED if order was fulfilled successfully;
     *         REQUIRES_RECONCILIATION if order requires manual reconciliation
     */
    private OrderApplicationResult applyOrder(PaymentOrder order, VerifiedPaymentNotification notification, PaymentEvent paymentEvent) {
        PricingSnapshot pricingSnapshot;
        try {
            pricingSnapshot = billingSnapshotCodec.readPricingSnapshot(order.getPricingSnapshot());
        } catch (RuntimeException exception) {
            order.setStatus(PaymentOrderStatus.REQUIRES_RECONCILIATION);
            paymentOrderRepository.save(order);
            billingReconciliationService.createCase(
                    order.getUserId(),
                    order.getId(),
                    paymentEvent.getId(),
                    order.getSubscriptionContractId(),
                    "PAYMENT_ORDER",
                    "SNAPSHOT_CORRUPT",
                    Map.of("orderNo", order.getOrderNo())
            );
            throw new BusinessException(ErrorCode.PAYMENT_005, "Payment order snapshot is corrupted");
        }
        if (order.getOrderType() == PaymentOrderType.CREDIT_PURCHASE) {
            if (pricingSnapshot.creditsAmountMicros() == null) {
                throw new BusinessException(ErrorCode.PAYMENT_005, "Purchase order snapshot is incomplete");
            }
            BillingEvent grantEvent = billingEventService.createOrGet(
                    order.getUserId(),
                    BillingEventType.CREDIT_PURCHASE_GRANTED,
                    "PAYMENT_EVENT",
                    notification.providerEventId(),
                    notification.provider() + "|" + notification.providerEventId() + "|credit-purchase",
                    pricingSnapshot.creditsAmountMicros(),
                    null,
                    notification.occurredAt(),
                    Map.of("orderNo", order.getOrderNo(), "providerOrderRef", notification.providerOrderRef())
            );
            creditBalanceProjectionService.grantPurchasedCredits(
                    order.getUserId(),
                    grantEvent,
                    pricingSnapshot.creditsAmountMicros(),
                    null,
                    Map.of("orderNo", order.getOrderNo(), "skuCode", pricingSnapshot.skuCode())
            );
            return OrderApplicationResult.FULFILLED;
        }
        if (order.getOrderType() == PaymentOrderType.SUBSCRIPTION_PURCHASE) {
            SubscriptionContract contract = subscriptionLifecycleService.applySubscriptionPurchase(order, notification);
            // If contract is null, order requires reconciliation (post-cutover missing reservation)
            if (contract == null) {
                return OrderApplicationResult.REQUIRES_RECONCILIATION;
            }
            return OrderApplicationResult.FULFILLED;
        }
        if (order.getOrderType() == PaymentOrderType.SUBSCRIPTION_RENEWAL) {
            SubscriptionContract contract = subscriptionLifecycleService.applySubscriptionRenewal(order, notification);
            // If contract is null, order requires reconciliation (post-cutover missing reservation)
            if (contract == null) {
                return OrderApplicationResult.REQUIRES_RECONCILIATION;
            }
            return OrderApplicationResult.FULFILLED;
        }
        throw new BusinessException(ErrorCode.PAYMENT_004, "Unsupported payment order type");
    }

    private void markApplied(PaymentEvent event) {
        event.setProcessStatus(PaymentEventProcessStatus.APPLIED);
        event.setProcessedAt(nowUtc());
        event.setLastErrorCode(null);
        event.setLastErrorMessage(null);
        paymentEventRepository.save(event);
    }

    private void markFailed(
            PaymentEvent event,
            PaymentEventProcessStatus status,
            String errorCode,
            String errorMessage
    ) {
        event.setProcessStatus(status);
        event.setLastErrorCode(errorCode);
        event.setLastErrorMessage(errorMessage);
        event.setProcessedAt(nowUtc());
        paymentEventRepository.save(event);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private PaymentEventSemantic classifyEvent(String eventType) {
        String normalized = normalizeEventType(eventType);
        if (SUCCESS_EVENT_TYPES.contains(normalized)) {
            return PaymentEventSemantic.SUCCESS;
        }
        if (PENDING_EVENT_TYPES.contains(normalized)) {
            return PaymentEventSemantic.PENDING;
        }
        if (normalized.contains("REFUND") || normalized.contains("CHARGEBACK") || normalized.contains("REVERSAL")) {
            return PaymentEventSemantic.REVERSAL;
        }
        return PaymentEventSemantic.UNSUPPORTED;
    }

    private String normalizeEventType(String eventType) {
        return eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
    }

    private enum PaymentEventSemantic {
        SUCCESS,
        PENDING,
        REVERSAL,
        UNSUPPORTED
    }
}
