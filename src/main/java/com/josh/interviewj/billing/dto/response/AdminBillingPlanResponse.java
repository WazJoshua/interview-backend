package com.josh.interviewj.billing.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AdminBillingPlanResponse {

    private String id;
    private String planCode;
    private String tierCode;
    private String displayName;
    private int tierRank;
    private boolean active;
    private Map<String, Object> metadata;
    private List<AdminBillingPlanVersionResponse> versions;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
