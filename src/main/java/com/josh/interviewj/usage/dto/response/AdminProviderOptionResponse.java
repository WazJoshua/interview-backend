package com.josh.interviewj.usage.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminProviderOptionResponse {

    private String id;
    private String provider;
    private String displayName;
    private Boolean enabled;
    private List<String> supportedUsageFamilies;
    private String sourceOfTruth;
    private String apiKeyMasked;
}
