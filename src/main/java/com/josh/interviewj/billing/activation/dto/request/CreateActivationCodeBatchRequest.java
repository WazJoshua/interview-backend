package com.josh.interviewj.billing.activation.dto.request;

import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@ValidActivationCodePayload
public class CreateActivationCodeBatchRequest {

    @NotNull
    @Min(1)
    @Max(500)
    private Integer count;

    @NotNull
    private ActivationCodeType codeType;

    private Long billingPlanVersionId;
    private Integer subscriptionDurationDays;
    private Long creditAmountMicros;
    private OffsetDateTime expiresAt;
    private String note;
}
