package com.josh.interviewj.usage.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class UpdateRoutingPolicyRequest {

    @NotBlank
    private String provider;

    @NotBlank
    private String modelCode;

    @NotBlank
    private String usageFamily;

    @NotNull
    private Boolean enabled;

    private Integer timeoutMs;
    private Integer maxRetries;
    private Map<String, Object> metadata;
}
