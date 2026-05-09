package com.josh.interviewj.billing.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.dto.request.CreateBillingOrderRequest;
import com.josh.interviewj.billing.dto.response.UserBillingOrderResponse;
import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanEntitlementItem;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.CreditPurchaseSku;
import com.josh.interviewj.billing.model.CreditPurchaseSkuVersion;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.provider.PaymentProviderRegistry;
import com.josh.interviewj.billing.repository.BillingPlanEntitlementItemRepository;
import com.josh.interviewj.billing.repository.BillingPlanRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.CreditPurchaseSkuRepository;
import com.josh.interviewj.billing.repository.CreditPurchaseSkuVersionRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import com.josh.interviewj.usage.service.CreditFormattingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class BillingOrderService {

    private final Clock clock;
    private final UserRepository userRepository;
    private final BillingProperties billingProperties;
    private final CreditFormattingService creditFormattingService;
    private final BillingSnapshotCodec billingSnapshotCodec;
    private final InventoryReservationService inventoryReservationService;
    private final BillingPlanRepository billingPlanRepository;
    private final BillingPlanVersionRepository billingPlanVersionRepository;
    private final BillingPlanEntitlementItemRepository entitlementItemRepository;
    private final CreditPurchaseSkuRepository creditPurchaseSkuRepository;
    private final CreditPurchaseSkuVersionRepository creditPurchaseSkuVersionRepository;
    private final SubscriptionContractRepository subscriptionContractRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentProviderRegistry paymentProviderRegistry;
    private final RuntimeSwitchService runtimeSwitchService;
    private final TransactionTemplate requiresNewTx;

    public BillingOrderService(
            Clock clock,
            UserRepository userRepository,
            BillingProperties billingProperties,
            CreditFormattingService creditFormattingService,
            BillingSnapshotCodec billingSnapshotCodec,
            InventoryReservationService inventoryReservationService,
            BillingPlanRepository billingPlanRepository,
            BillingPlanVersionRepository billingPlanVersionRepository,
            BillingPlanEntitlementItemRepository entitlementItemRepository,
            CreditPurchaseSkuRepository creditPurchaseSkuRepository,
            CreditPurchaseSkuVersionRepository creditPurchaseSkuVersionRepository,
            SubscriptionContractRepository subscriptionContractRepository,
            PaymentOrderRepository paymentOrderRepository,
            PaymentProviderRegistry paymentProviderRegistry,
            RuntimeSwitchService runtimeSwitchService,
            PlatformTransactionManager txManager
    ) {
        this.clock = clock;
        this.userRepository = userRepository;
        this.billingProperties = billingProperties;
        this.creditFormattingService = creditFormattingService;
        this.billingSnapshotCodec = billingSnapshotCodec;
        this.inventoryReservationService = inventoryReservationService;
        this.billingPlanRepository = billingPlanRepository;
        this.billingPlanVersionRepository = billingPlanVersionRepository;
        this.entitlementItemRepository = entitlementItemRepository;
        this.creditPurchaseSkuRepository = creditPurchaseSkuRepository;
        this.creditPurchaseSkuVersionRepository = creditPurchaseSkuVersionRepository;
        this.subscriptionContractRepository = subscriptionContractRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentProviderRegistry = paymentProviderRegistry;
        this.runtimeSwitchService = runtimeSwitchService;
        TransactionTemplate requiresNew = new TransactionTemplate(txManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = requiresNew;
    }

    public UserBillingOrderResponse createOrder(String username, CreateBillingOrderRequest request) {
        runtimeSwitchService.requirePaymentEnabled();
        User user = requireUser(username);
        PaymentOrderType orderType = parseOrderType(request.getOrderType());
        LocalDateTime now = nowUtc();
        paymentProviderRegistry.requireAdapter(request.getProvider());

        try {
            return requiresNewTx.execute(status -> {
                PaymentOrder blockingOrder = paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(user.getId()).stream()
                        .findFirst()
                        .orElse(null);
                if (blockingOrder != null) {
                    if (samePurchaseIntent(blockingOrder, request, orderType)) {
                        return toUserBillingOrderResponse(blockingOrder, true);
                    }
                    throw new BusinessException(ErrorCode.PAYMENT_004, "Another billing order is already awaiting payment");
                }

                PaymentOrder existing = paymentOrderRepository.findByUserIdAndIdempotencyKey(user.getId(), request.getIdempotencyKey()).orElse(null);
                if (existing != null) {
                    validateReusableOrder(existing, request, orderType, user.getId());
                    if (existing.getExpiresAt().isAfter(now)
                            && existing.getStatus() != PaymentOrderStatus.EXPIRED
                            && existing.getStatus() != PaymentOrderStatus.CANCELED
                            && existing.getStatus() != PaymentOrderStatus.FAILED) {
                        return toUserBillingOrderResponse(existing, true);
                    }
                    throw new BusinessException(ErrorCode.PAYMENT_004, "Expired or closed billing order cannot be reused");
                }

                return switch (orderType) {
                    case SUBSCRIPTION_PURCHASE -> createSubscriptionPurchaseOrder(user, request, now);
                    case CREDIT_PURCHASE -> createCreditPurchaseOrder(user, request, now);
                    case SUBSCRIPTION_RENEWAL ->
                            throw new BusinessException(ErrorCode.PAYMENT_004, "Subscription renewal orders are system managed");
                };
            });
        } catch (DataIntegrityViolationException e) {
            return handleConcurrentOrderConflict(user.getId(), request, orderType, e);
        }
    }

    public UserBillingOrderResponse getOrder(String username, String orderNo) {
        User user = requireUser(username);
        PaymentOrder order = paymentOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_002, "Billing order not found"));
        if (!order.getUserId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.PAYMENT_002, "Billing order not found");
        }
        return toUserBillingOrderResponse(order);
    }

    public List<UserBillingOrderResponse> getCurrentUserActiveOrders(String username) {
        User user = requireUser(username);
        return paymentOrderRepository.findActivePayableOrdersByUserId(user.getId()).stream()
                .map(this::toUserBillingOrderResponse)
                .toList();
    }

    public UserBillingOrderResponse toUserBillingOrderResponse(PaymentOrder order) {
        return toUserBillingOrderResponse(order, false);
    }

    private void validateReusableOrder(
            PaymentOrder existing,
            CreateBillingOrderRequest request,
            PaymentOrderType orderType,
            Long userId
    ) {
        if (!existing.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.PAYMENT_002, "Billing order not found");
        }
        if (existing.getOrderType() != orderType || !sameProvider(existing.getProvider(), request.getProvider())) {
            throw differentRequestReuseException();
        }

        switch (orderType) {
            case SUBSCRIPTION_PURCHASE -> {
                if (!"BILLING_PLAN".equals(existing.getBizRefType())
                        || request.getPlanCode() == null
                        || !existing.getBizRefId().equals(request.getPlanCode())) {
                    throw differentRequestReuseException();
                }
            }
            case CREDIT_PURCHASE -> {
                if (!"CREDIT_PURCHASE_SKU".equals(existing.getBizRefType())
                        || request.getPurchaseSkuCode() == null
                        || !existing.getBizRefId().equals(request.getPurchaseSkuCode())) {
                    throw differentRequestReuseException();
                }
            }
            case SUBSCRIPTION_RENEWAL -> throw differentRequestReuseException();
        }
    }

    private UserBillingOrderResponse createSubscriptionPurchaseOrder(
            User user,
            CreateBillingOrderRequest request,
            LocalDateTime now
    ) {
        if (request.getPlanCode() == null || request.getPlanCode().isBlank()) {
            throw new BusinessException(ErrorCode.USER_BILLING_003, "planCode is required for subscription purchase");
        }
        if (subscriptionContractRepository.findOpenContractByUserId(user.getId()).isPresent()) {
            throw new BusinessException(ErrorCode.USER_BILLING_002, "Open subscription contract already exists");
        }
        BillingPlan plan = billingPlanRepository.findByPlanCode(request.getPlanCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_BILLING_003, "Billing plan not found"));
        BillingPlanVersion version = billingPlanVersionRepository.findActiveSellableVersion(plan.getId(), now)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_BILLING_003, "Billing plan version not found"));
        List<BillingPlanEntitlementItem> items = entitlementItemRepository.findByBillingPlanVersionIdOrderByBucketCodeAsc(version.getId());
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_001, "Billing plan version does not contain entitlement items");
        }

        // Step 1: Reserve inventory atomically BEFORE order creation (reserve-first pattern)
        // Use 'now' as the expected order creation time for legacy check
        // If reservation fails (insufficient inventory), transaction rolls back immediately
        boolean reservationNeeded = inventoryReservationService.tryReserveInventory(
                version.getId(),
                version,
                now
        );

        // Step 2: Create order after inventory is secured
        PaymentOrder order = PaymentOrder.builder()
                .externalId(UUID.randomUUID())
                .orderNo("po_" + UUID.randomUUID().toString().replace("-", ""))
                .userId(user.getId())
                .orderType(PaymentOrderType.SUBSCRIPTION_PURCHASE)
                .bizRefType("BILLING_PLAN")
                .bizRefId(plan.getPlanCode())
                .lockedPlanVersionId(version.getId())
                .provider(request.getProvider())
                .amount(version.getAmount())
                .currency(version.getCurrency())
                .status(PaymentOrderStatus.CREATED)
                .idempotencyKey(request.getIdempotencyKey())
                .providerOrderRef(null)
                .pricingSnapshot(billingSnapshotCodec.writePlanPricingSnapshot(plan, version))
                .entitlementSnapshot(billingSnapshotCodec.writeEntitlementSnapshot(items))
                .payableActivatedAt(now)
                .expiresAt(now.plusMinutes(billingProperties.getOrder().getDefaultExpireMinutes()))
                .build();
        PaymentOrder savedOrder = paymentOrderRepository.saveAndFlush(order);

        // Step 3: Create reservation record binding the secured inventory to the order
        // Use actual order creation timestamp for accurate record keeping
        inventoryReservationService.createReservationRecord(
                savedOrder.getId(),
                version.getId(),
                reservationNeeded
        );

        // Set provider order reference
        savedOrder.setProviderOrderRef(savedOrder.getOrderNo());
        return toUserBillingOrderResponse(paymentOrderRepository.save(savedOrder));
    }

    private UserBillingOrderResponse createCreditPurchaseOrder(
            User user,
            CreateBillingOrderRequest request,
            LocalDateTime now
    ) {
        if (request.getPurchaseSkuCode() == null || request.getPurchaseSkuCode().isBlank()) {
            throw new BusinessException(ErrorCode.USER_BILLING_003, "purchaseSkuCode is required for credit purchase");
        }
        CreditPurchaseSku sku = creditPurchaseSkuRepository.findBySkuCode(request.getPurchaseSkuCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_BILLING_003, "Credit purchase sku not found"));
        CreditPurchaseSkuVersion version = creditPurchaseSkuVersionRepository.findActiveSellableVersion(sku.getId(), now)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_BILLING_003, "Credit purchase sku version not found"));

        PaymentOrder order = paymentOrderRepository.saveAndFlush(PaymentOrder.builder()
                .externalId(UUID.randomUUID())
                .orderNo("po_" + UUID.randomUUID().toString().replace("-", ""))
                .userId(user.getId())
                .orderType(PaymentOrderType.CREDIT_PURCHASE)
                .bizRefType("CREDIT_PURCHASE_SKU")
                .bizRefId(sku.getSkuCode())
                .lockedPurchaseSkuVersionId(version.getId())
                .provider(request.getProvider())
                .amount(version.getAmount())
                .currency(version.getCurrency())
                .status(PaymentOrderStatus.CREATED)
                .idempotencyKey(request.getIdempotencyKey())
                .providerOrderRef(null)
                .pricingSnapshot(billingSnapshotCodec.writePurchasePricingSnapshot(sku, version))
                .entitlementSnapshot("[]")
                .payableActivatedAt(now)
                .expiresAt(now.plusMinutes(billingProperties.getOrder().getDefaultExpireMinutes()))
                .build());
        order.setProviderOrderRef(order.getOrderNo());
        return toUserBillingOrderResponse(paymentOrderRepository.save(order));
    }

    private UserBillingOrderResponse toUserBillingOrderResponse(PaymentOrder order, boolean reused) {
        return UserBillingOrderResponse.builder()
                .orderNo(order.getOrderNo())
                .status(order.getStatus().name())
                .provider(order.getProvider())
                .orderType(order.getOrderType().name())
                .bizRefType(order.getBizRefType())
                .bizRefId(order.getBizRefId())
                .amount(creditFormattingService.formatAmount(order.getAmount()))
                .currency(order.getCurrency())
                .expiresAt(toOffset(order.getExpiresAt()))
                .paidAt(toOffset(order.getPaidAt()))
                .payableActivatedAt(toOffset(order.getPayableActivatedAt()))
                .reused(reused)
                .pricingSnapshot(billingSnapshotCodec.readMap(order.getPricingSnapshot()))
                .build();
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003, "User not found"));
    }

    private PaymentOrderType parseOrderType(String rawValue) {
        try {
            return PaymentOrderType.valueOf(rawValue);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PAYMENT_004, "Unsupported billing order type");
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private BusinessException differentRequestReuseException() {
        return new BusinessException(ErrorCode.PAYMENT_004, "Idempotency key already belongs to a different billing request");
    }

    private boolean samePurchaseIntent(
            PaymentOrder existing,
            CreateBillingOrderRequest request,
            PaymentOrderType orderType
    ) {
        if (existing.getOrderType() != orderType || !sameProvider(existing.getProvider(), request.getProvider())) {
            return false;
        }
        return switch (orderType) {
            case SUBSCRIPTION_PURCHASE -> "BILLING_PLAN".equals(existing.getBizRefType())
                    && request.getPlanCode() != null
                    && request.getPlanCode().equals(existing.getBizRefId());
            case CREDIT_PURCHASE -> "CREDIT_PURCHASE_SKU".equals(existing.getBizRefType())
                    && request.getPurchaseSkuCode() != null
                    && request.getPurchaseSkuCode().equals(existing.getBizRefId());
            case SUBSCRIPTION_RENEWAL -> false;
        };
    }

    private boolean sameProvider(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    /**
     * Handles DataIntegrityViolationException from saveAndFlush by performing
     * an idempotent lookup in a REQUIRES_NEW transaction (since the original
     * transaction is rollback-only after a constraint violation).
     *
     * Lookup strategy:
     * 1. Try userId + idempotencyKey — same client retrying the same request.
     * 2. Try userId active payable orders — another concurrent request won the race.
     *    If same purchase intent, return it as reused; otherwise throw conflict.
     */
    private UserBillingOrderResponse handleConcurrentOrderConflict(
            Long userId,
            CreateBillingOrderRequest request,
            PaymentOrderType orderType,
            DataIntegrityViolationException cause
    ) {
        log.warn("Concurrent order conflict for userId={}, idempotencyKey={}: {}",
                userId, request.getIdempotencyKey(), cause.getMessage());

        UserBillingOrderResponse result = requiresNewTx.execute(status -> {
            // Step 1: check by idempotency key
            PaymentOrder byKey = paymentOrderRepository
                    .findByUserIdAndIdempotencyKey(userId, request.getIdempotencyKey())
                    .orElse(null);
            if (byKey != null && samePurchaseIntent(byKey, request, orderType)) {
                return toUserBillingOrderResponse(byKey, true);
            }

            // Step 2: check active payable orders for same purchase intent
            PaymentOrder activeOrder = paymentOrderRepository
                    .findActivePayableOrdersByUserId(userId).stream()
                    .findFirst()
                    .orElse(null);
            if (activeOrder != null && samePurchaseIntent(activeOrder, request, orderType)) {
                return toUserBillingOrderResponse(activeOrder, true);
            }

            // Neither match — genuine conflict
            return null;
        });

        if (result != null) {
            return result;
        }
        throw new BusinessException(ErrorCode.PAYMENT_004, "Another billing order is already awaiting payment");
    }

}
