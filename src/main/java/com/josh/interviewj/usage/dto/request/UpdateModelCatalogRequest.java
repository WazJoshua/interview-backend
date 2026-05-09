package com.josh.interviewj.usage.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class UpdateModelCatalogRequest {

    private String displayName;

    @NotNull
    private Boolean active;

    private Map<String, Object> metadata;
}
