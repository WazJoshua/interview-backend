package com.josh.interviewj.billing.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.dto.request.StartBillingOrderPaymentRequest;
import com.josh.interviewj.billing.dto.response.StartBillingOrderPaymentResponse;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.provider.PaymentInitiationRequest;
import com.josh.interviewj.billing.provider.PaymentInitiationResult;
import com.josh.interviewj.billing.provider.PaymentProviderAdapter;
import com.josh.interviewj.billing.provider.PaymentProviderRegistry;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillingPaymentService {

    private final Clock clock;
    private final BillingProperties billingProperties;
    private final UserRepository userRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentProviderRegistry paymentProviderRegistry;
    private final RuntimeSwitchService runtimeSwitchService;

    @Transactional
    public StartBillingOrderPaymentResponse startPayment(
            String username,
            String orderNo,
            StartBillingOrderPaymentRequest request
    ) {
        runtimeSwitchService.requirePaymentEnabled();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003, "User not found"));

        PaymentOrder targetOrder = paymentOrderRepository.findByOrderNoAndUserId(orderNo, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found"));
        activateRenewalOrderIfNeeded(targetOrder);

        List<PaymentOrder> activeOrders = paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(user.getId());

        validatePayableOrder(targetOrder);
        boolean hasOtherBlockingOrder = activeOrders.stream()
                .anyMatch(order -> !order.getId().equals(targetOrder.getId()));
        if (hasOtherBlockingOrder) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Another billing order is already awaiting payment");
        }

        PaymentProviderAdapter adapter = paymentProviderRegistry.requireAdapter(targetOrder.getProvider());
        PaymentInitiationResult initiationResult = adapter.initiatePayment(new PaymentInitiationRequest(
                targetOrder.getOrderNo(),
                targetOrder.getAmount(),
                targetOrder.getCurrency(),
                request.getTerminal(),
                buildSubject(targetOrder),
                request.getReturnUrl()
        ));

        targetOrder.setStatus(PaymentOrderStatus.PENDING_PROVIDER);
        if (initiationResult.providerOrderRef() != null && !initiationResult.providerOrderRef().isBlank()) {
            targetOrder.setProviderOrderRef(initiationResult.providerOrderRef());
        }
        paymentOrderRepository.save(targetOrder);

        return new StartBillingOrderPaymentResponse(
                targetOrder.getOrderNo(),
                targetOrder.getStatus().name(),
                initiationResult.redirectUrl()
        );
    }

    private void validatePayableOrder(PaymentOrder order) {
        if (order.getPayableActivatedAt() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Billing order is not activated for payment");
        }
        if (order.getStatus() != PaymentOrderStatus.CREATED && order.getStatus() != PaymentOrderStatus.PENDING_PROVIDER) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Billing order status does not allow payment initiation");
        }
    }

    private void activateRenewalOrderIfNeeded(PaymentOrder order) {
        if (order.getOrderType() != PaymentOrderType.SUBSCRIPTION_RENEWAL || order.getPayableActivatedAt() != null) {
            return;
        }
        LocalDateTime now = nowUtc();
        order.setPayableActivatedAt(now);
        order.setExpiresAt(now.plusMinutes(billingProperties.getOrder().getDefaultExpireMinutes()));
        paymentOrderRepository.save(order);
    }

    private String buildSubject(PaymentOrder order) {
        return "InterviewJ " + order.getBizRefId();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
