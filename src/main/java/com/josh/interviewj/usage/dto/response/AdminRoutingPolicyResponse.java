package com.josh.interviewj.usage.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AdminRoutingPolicyResponse {

    private String id;
    private String purpose;
    private String usageFamily;
    private String provider;
    private String modelCode;
    private Boolean enabled;
    private String strategy;
    private Integer timeoutMs;
    private Integer maxRetries;
    private Map<String, Object> metadata;
    private List<String> fallback;
    private String sourceOfTruth;
    private Boolean editable;
}
