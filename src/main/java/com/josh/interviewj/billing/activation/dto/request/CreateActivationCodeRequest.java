package com.josh.interviewj.billing.activation.dto.request;

import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@ValidActivationCodePayload
public class CreateActivationCodeRequest {

    @NotNull
    private ActivationCodeType codeType;

    private Long billingPlanVersionId;
    private Integer subscriptionDurationDays;
    private Long creditAmountMicros;
    private OffsetDateTime expiresAt;
    private String note;
}
