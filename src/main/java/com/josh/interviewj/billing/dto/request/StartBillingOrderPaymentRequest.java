package com.josh.interviewj.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StartBillingOrderPaymentRequest {

    @NotBlank
    private String terminal;

    private String returnUrl;
}
