package com.josh.interviewj.usage.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class UpdateProviderRequest {

    @NotBlank
    private String displayName;

    private String baseUrl;
    private String templateRoot;

    @NotNull
    private Boolean enabled;

    private Integer defaultTimeoutMs;
    private Integer defaultMaxRetries;

    @NotEmpty
    private List<String> supportedUsageFamilies;

    private Map<String, Object> metadata;

    private String apiKey;
}
