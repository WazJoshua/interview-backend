package com.josh.interviewj.usage.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class AdminModelCatalogQuery {

    private String provider;
    private String usageFamily;
    private Boolean activeOnly = false;

    @Min(0)
    private Integer page = 0;

    @Min(1)
    @Max(100)
    private Integer size = 20;
}
