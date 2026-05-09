package com.josh.interviewj.billing.service;

import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.dto.request.AdminReconciliationDecisionRequest;
import com.josh.interviewj.billing.dto.response.AdminBillingReconciliationCaseResponse;
import com.josh.interviewj.billing.model.BillingReconciliationCase;
import com.josh.interviewj.billing.repository.BillingReconciliationCaseRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingReconciliationService {

    private final Clock clock;
    private final BillingSnapshotCodec billingSnapshotCodec;
    private final UserRepository userRepository;
    private final AdminOperationLogService adminOperationLogService;
    private final BillingReconciliationCaseRepository reconciliationCaseRepository;
    private final BillingReconciliationResolutionExecutor reconciliationResolutionExecutor;

    @Transactional
    public BillingReconciliationCase createCase(
            Long userId,
            Long paymentOrderId,
            Long paymentEventId,
            Long subscriptionContractId,
            String caseType,
            String reasonCode,
            Map<String, Object> details
    ) {
        return reconciliationCaseRepository.save(BillingReconciliationCase.builder()
                .externalId(UUID.randomUUID())
                .userId(userId)
                .paymentOrderId(paymentOrderId)
                .paymentEventId(paymentEventId)
                .subscriptionContractId(subscriptionContractId)
                .caseType(caseType)
                .status("OPEN")
                .reasonCode(reasonCode)
                .details(billingSnapshotCodec.write(details == null ? Map.of() : details))
                .build());
    }

    public List<AdminBillingReconciliationCaseResponse> getOpenCases() {
        return reconciliationCaseRepository.findByStatusOrderByCreatedAtDescIdDesc("OPEN").stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AdminBillingReconciliationCaseResponse resolveCase(
            Long actorUserId,
            Long caseId,
            AdminReconciliationDecisionRequest request
    ) {
        BillingReconciliationCase reconciliationCase = reconciliationCaseRepository.findById(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_BILLING_003, "Billing reconciliation case not found"));
        if ("RESOLVED".equalsIgnoreCase(reconciliationCase.getStatus())) {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_004, "Billing reconciliation case has already been resolved");
        }
        BillingReconciliationCase before = BillingReconciliationCase.builder()
                .id(reconciliationCase.getId())
                .status(reconciliationCase.getStatus())
                .reasonCode(reconciliationCase.getReasonCode())
                .resolutionCode(reconciliationCase.getResolutionCode())
                .build();
        reconciliationResolutionExecutor.execute(reconciliationCase, request.getResolutionCode(), request.getMetadata());
        reconciliationCase.setStatus("RESOLVED");
        reconciliationCase.setResolutionCode(request.getResolutionCode());
        reconciliationCase.setResolvedByUserId(actorUserId);
        reconciliationCase.setResolvedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        Map<String, Object> mergedDetails = new java.util.LinkedHashMap<>(billingSnapshotCodec.readMap(reconciliationCase.getDetails()));
        if (request.getMetadata() != null) {
            mergedDetails.putAll(request.getMetadata());
        }
        mergedDetails.put("resolutionCode", request.getResolutionCode());
        reconciliationCase.setDetails(billingSnapshotCodec.write(mergedDetails));
        reconciliationCase = reconciliationCaseRepository.save(reconciliationCase);
        adminOperationLogService.recordUpdate(
                actorUserId,
                AdminOperationResourceType.BILLING_RECONCILIATION_CASE,
                String.valueOf(caseId),
                request.getRequestId(),
                before,
                reconciliationCase,
                Map.of("resolutionCode", request.getResolutionCode())
        );
        return toResponse(reconciliationCase);
    }

    private AdminBillingReconciliationCaseResponse toResponse(BillingReconciliationCase reconciliationCase) {
        String userExternalId = reconciliationCase.getUserId() == null ? null : userRepository.findById(reconciliationCase.getUserId())
                .map(user -> user.getExternalId().toString())
                .orElse(null);
        return AdminBillingReconciliationCaseResponse.builder()
                .id(String.valueOf(reconciliationCase.getId()))
                .status(reconciliationCase.getStatus())
                .caseType(reconciliationCase.getCaseType())
                .reasonCode(reconciliationCase.getReasonCode())
                .resolutionCode(reconciliationCase.getResolutionCode())
                .paymentOrderId(stringify(reconciliationCase.getPaymentOrderId()))
                .paymentEventId(stringify(reconciliationCase.getPaymentEventId()))
                .subscriptionContractId(stringify(reconciliationCase.getSubscriptionContractId()))
                .userId(userExternalId)
                .details(billingSnapshotCodec.readMap(reconciliationCase.getDetails()))
                .resolvedAt(toOffset(reconciliationCase.getResolvedAt()))
                .createdAt(toOffset(reconciliationCase.getCreatedAt()))
                .updatedAt(toOffset(reconciliationCase.getUpdatedAt()))
                .build();
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
