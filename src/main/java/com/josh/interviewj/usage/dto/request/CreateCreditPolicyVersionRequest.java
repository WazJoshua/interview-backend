package com.josh.interviewj.usage.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class CreateCreditPolicyVersionRequest {

    @NotBlank
    private String purpose;

    @NotBlank
    private String chargeBucket;

    @NotBlank
    private String usageFamily;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime effectiveFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime effectiveTo;

    @NotBlank
    private String billingUnit;

    private BigDecimal promptTokenRatio;
    private BigDecimal completionTokenRatio;
    private BigDecimal cachedTokenRatio;
    private BigDecimal requestRatio;

    private String metadata;

    @AssertTrue(message = "effectiveTo must be later than effectiveFrom")
    public boolean isValidEffectiveRange() {
        return effectiveTo == null || effectiveFrom == null || effectiveTo.isAfter(effectiveFrom);
    }
}
