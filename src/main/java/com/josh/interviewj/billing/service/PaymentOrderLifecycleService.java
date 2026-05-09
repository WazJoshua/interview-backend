package com.josh.interviewj.billing.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.model.BillingInventoryReservation;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.InventoryReservationStatus;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.repository.BillingInventoryReservationRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for handling payment order lifecycle events.
 * Implements reservation release for terminal order states (expired, canceled, failed).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentOrderLifecycleService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final BillingInventoryReservationRepository reservationRepository;
    private final BillingPlanVersionRepository planVersionRepository;
    private final InventoryReservationService inventoryReservationService;
    private final UserRepository userRepository;

    /**
     * Process expired orders: mark as EXPIRED and release any active reservations.
     *
     * @param cutoffTimestamp orders with expiresAt before this time are considered expired
     * @param batchSize maximum number of orders to process in one batch
     * @return count of processed orders
     */
    @Transactional
    public int processExpiredOrders(LocalDateTime cutoffTimestamp, int batchSize) {
        List<PaymentOrder> expiredOrders = paymentOrderRepository.findExpirableOrders(cutoffTimestamp, batchSize);

        int processedCount = 0;
        for (PaymentOrder order : expiredOrders) {
            try {
                processOrderExpiration(order);
                processedCount++;
            } catch (Exception e) {
                log.warn("Failed to process expired order: orderId={}, error={}",
                        order.getId(), e.getMessage());
            }
        }

        if (processedCount > 0) {
            log.info("Processed {} expired orders", processedCount);
        }

        return processedCount;
    }

    /**
     * Process a single order expiration.
     * Marks order as EXPIRED and releases any active reservation.
     *
     * @param order the order to expire
     */
    @Transactional
    public void processOrderExpiration(PaymentOrder order) {
        if (order.getStatus() == PaymentOrderStatus.SUCCEEDED) {
            log.debug("Order already succeeded, skipping expiration: orderId={}", order.getId());
            return;
        }

        // Release reservation if exists
        releaseReservationForOrder(order);

        // Update order status
        order.setStatus(PaymentOrderStatus.EXPIRED);
        order.setPayableActivatedAt(null);
        paymentOrderRepository.save(order);

        log.info("Order expired: orderId={}, orderNo={}", order.getId(), order.getOrderNo());
    }

    /**
     * Cancel an order and release any active reservation.
     *
     * @param orderId the order ID to cancel
     */
    @Transactional
    public void cancelOrder(Long orderId) {
        PaymentOrder order = paymentOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found"));
        cancelOrder(order);
    }

    @Transactional
    public PaymentOrder cancelCurrentUserOrder(String username, String orderNo, String reason) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003, "User not found"));
        PaymentOrder order = paymentOrderRepository.findByOrderNoAndUserId(orderNo, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found"));
        cancelOrder(order);
        log.info("Current user canceled order: username={}, orderNo={}, reason={}", username, orderNo, reason);
        return order;
    }

    private void cancelOrder(PaymentOrder order) {
        if (order.getStatus() == PaymentOrderStatus.SUCCEEDED) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Cannot cancel a succeeded order");
        }

        // Release reservation if exists
        releaseReservationForOrder(order);

        // Update order status
        order.setStatus(PaymentOrderStatus.CANCELED);
        order.setPayableActivatedAt(null);
        paymentOrderRepository.save(order);

        log.info("Order canceled: orderId={}, orderNo={}", order.getId(), order.getOrderNo());
    }

    /**
     * Mark an order as failed and release any active reservation.
     *
     * @param orderId the order ID to mark as failed
     * @param reason the failure reason
     */
    @Transactional
    public void markOrderFailed(Long orderId, String reason) {
        PaymentOrder order = paymentOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found"));

        if (order.getStatus() == PaymentOrderStatus.SUCCEEDED) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Cannot mark a succeeded order as failed");
        }

        // Release reservation if exists
        releaseReservationForOrder(order);

        // Update order status
        order.setStatus(PaymentOrderStatus.FAILED);
        order.setPayableActivatedAt(null);
        paymentOrderRepository.save(order);

        log.info("Order marked as failed: orderId={}, orderNo={}, reason={}",
                order.getId(), order.getOrderNo(), reason);
    }

    /**
     * Release any active reservation for an order.
     * Idempotent: safe to call multiple times.
     *
     * @param order the order to release reservation for
     */
    private void releaseReservationForOrder(PaymentOrder order) {
        if (order.getLockedPlanVersionId() == null) {
            log.debug("No locked plan version, skipping reservation release: orderId={}", order.getId());
            return;
        }

        // Check if active reservation exists
        BillingInventoryReservation reservation = reservationRepository
                .findByPaymentOrderIdAndStatus(order.getId(), InventoryReservationStatus.RESERVED)
                .orElse(null);

        if (reservation == null) {
            log.debug("No active reservation to release: orderId={}", order.getId());
            return;
        }

        // Check if inventory control was enabled when order was created
        BillingPlanVersion version = planVersionRepository.findById(order.getLockedPlanVersionId())
                .orElse(null);

        if (version == null || version.getInventoryControlEnabledAt() == null) {
            log.debug("Inventory control not enabled for plan version: orderId={}, planVersionId={}",
                    order.getId(), order.getLockedPlanVersionId());
            return;
        }

        // Check if order was created after inventory control was enabled
        if (order.getCreatedAt().isBefore(version.getInventoryControlEnabledAt())) {
            log.debug("Legacy order created before inventory control, skipping release: orderId={}", order.getId());
            return;
        }

        // Release the reservation
        inventoryReservationService.releaseForOrder(order.getId(), order.getLockedPlanVersionId());
    }
}
