package com.josh.interviewj.usage.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

@Data
public class CreatePricingVersionRequest {

    @NotBlank
    private String provider;

    @NotBlank
    private String modelCode;

    @NotBlank
    private String usageFamily;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime effectiveFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime effectiveTo;

    @NotBlank
    private String billingUnit;

    @PositiveOrZero
    private BigDecimal promptTokenPrice;

    @PositiveOrZero
    private BigDecimal completionTokenPrice;

    @PositiveOrZero
    private BigDecimal cachedTokenPrice;

    @PositiveOrZero
    private BigDecimal requestPrice;

    @NotBlank
    private String currency;

    private Map<String, Object> metadata;

    @AssertTrue(message = "effectiveTo must be later than effectiveFrom")
    public boolean isValidEffectiveRange() {
        return effectiveTo == null || effectiveFrom == null || effectiveTo.isAfter(effectiveFrom);
    }
}
