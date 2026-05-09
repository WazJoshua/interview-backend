package com.josh.interviewj.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
public class AdminManualAdjustmentRequest {

    @NotNull
    private UUID userId;

    @NotNull
    private Long deltaAmountMicros;

    private String bucketCode;

    @NotBlank
    private String reason;

    private String requestId;

    private Map<String, Object> metadata;
}
