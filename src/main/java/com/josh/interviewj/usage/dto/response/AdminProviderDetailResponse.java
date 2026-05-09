package com.josh.interviewj.usage.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AdminProviderDetailResponse {

    private String id;
    private String provider;
    private String displayName;
    private String baseUrl;
    private String templateRoot;
    private Boolean enabled;
    private Integer defaultTimeoutMs;
    private Integer defaultMaxRetries;
    private List<String> supportedUsageFamilies;
    private Map<String, Object> metadata;
    private String apiKeyMasked;
    private String sourceOfTruth;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
