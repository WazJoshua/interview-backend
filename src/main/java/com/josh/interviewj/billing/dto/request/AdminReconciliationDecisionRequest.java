package com.josh.interviewj.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

@Data
public class AdminReconciliationDecisionRequest {

    @NotBlank
    @Pattern(regexp = "FULFILL_MANUALLY|REFUND_MANUALLY|CLOSE_NO_ACTION")
    private String resolutionCode;

    private String requestId;

    private Map<String, Object> metadata;
}
