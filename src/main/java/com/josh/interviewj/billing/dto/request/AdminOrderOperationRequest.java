package com.josh.interviewj.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdminOrderOperationRequest {

    @NotBlank
    private String reason;
}