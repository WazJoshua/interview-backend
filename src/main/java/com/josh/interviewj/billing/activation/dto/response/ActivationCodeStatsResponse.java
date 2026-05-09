package com.josh.interviewj.billing.activation.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ActivationCodeStatsResponse {

    private long total;
    private long unused;
    private long redeemed;
    private long voided;
    private long expired;
}
