package com.josh.interviewj.billing.activation.dto.response;

import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class RedeemResultResponse {

    private ActivationCodeType codeType;
    private String grantedPlanName;
    private Integer grantedDurationDays;
    private OffsetDateTime subscriptionExpiresAt;
    private Long grantedCredits;
}
