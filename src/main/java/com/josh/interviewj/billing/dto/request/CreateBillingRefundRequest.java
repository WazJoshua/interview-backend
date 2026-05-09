package com.josh.interviewj.billing.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateBillingRefundRequest {

    @NotBlank
    private String orderNo;

    @NotNull
    @DecimalMin(value = "0.000001")
    @Digits(integer = 18, fraction = 6)
    private BigDecimal requestedAmount;

    @NotBlank
    private String reason;
}
