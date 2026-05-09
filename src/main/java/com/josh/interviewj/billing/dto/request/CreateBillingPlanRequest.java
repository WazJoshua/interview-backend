package com.josh.interviewj.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class CreateBillingPlanRequest {

    @NotBlank
    private String planCode;

    @NotBlank
    private String tierCode;

    @NotBlank
    private String displayName;

    private Integer tierRank;

    private Boolean active = Boolean.TRUE;

    private Map<String, Object> metadata;
}
