package com.josh.interviewj.billing.activation.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RedeemActivationCodeRequest {

    @NotBlank
    private String code;
}
