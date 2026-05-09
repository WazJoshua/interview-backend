package com.josh.interviewj.usage.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
public class AdminModelCatalogResponse {

    private String id;
    private String provider;
    private String modelCode;
    private String usageFamily;
    private String displayName;
    private Boolean active;
    private Map<String, Object> metadata;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
