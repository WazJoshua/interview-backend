package com.josh.interviewj.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateBillingOrderRequest {

    @NotBlank
    private String orderType;

    @NotBlank
    private String provider;

    @NotBlank
    private String idempotencyKey;

    private String planCode;

    private String purchaseSkuCode;
}
