package com.josh.interviewj.usage.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class AdminPricingVersionQuery {

    private String provider;
    private String modelCode;
    private String usageFamily;
    private Boolean currentOnly = false;

    @Min(0)
    private Integer page = 0;

    @Min(1)
    @Max(100)
    private Integer size = 20;
}
