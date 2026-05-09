package com.josh.interviewj.usage.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class LlmHealthCheckResponse {

    private Boolean healthy;
    private Long databaseVersion;
    private Long cachedVersion;
    private Integer providerCount;
    private Integer routingCount;
    private Map<String, Long> secretKeyVersionStats;
    private List<String> issues;
}
