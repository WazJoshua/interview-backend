package com.josh.interviewj.billing.activation.dto.response;

import com.josh.interviewj.billing.activation.model.ActivationCodeStatus;
import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class ActivationCodeView {

    private Long id;
    private String code;
    private ActivationCodeType codeType;
    private ActivationCodeStatus status;
    private Long billingPlanVersionId;
    private Integer subscriptionDurationDays;
    private Long creditAmountMicros;
    private OffsetDateTime expiresAt;
    private Long redeemedByUserId;
    private OffsetDateTime redeemedAt;
    private UUID batchId;
    private Long createdByUserId;
    private String note;
    private OffsetDateTime createdAt;
}
