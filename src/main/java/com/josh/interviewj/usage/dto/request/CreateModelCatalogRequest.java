package com.josh.interviewj.usage.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class CreateModelCatalogRequest {

    @NotBlank
    private String provider;

    @NotBlank
    private String modelCode;

    @NotBlank
    private String usageFamily;

    private String displayName;
    private Boolean active;
    private Map<String, Object> metadata;
}
