package com.josh.interviewj.billing.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
public class CreateBillingPlanVersionRequest {

    @NotNull
    @Min(1)
    private Integer versionNo;

    @NotBlank
    private String billingCycle;

    @NotNull
    @DecimalMin(value = "0.000001")
    private BigDecimal amount;

    @NotBlank
    private String currency;

    private Boolean saleEnabled = Boolean.TRUE;

    private Boolean renewalEnabled = Boolean.TRUE;

    @NotNull
    private OffsetDateTime effectiveFrom;

    private OffsetDateTime effectiveTo;

    @Valid
    @NotEmpty
    private List<EntitlementItemRequest> entitlementItems;

    private Map<String, Object> metadata;

    @AssertTrue(message = "effectiveTo must be after effectiveFrom")
    @JsonIgnore
    public boolean isEffectiveRangeValid() {
        return effectiveTo == null || effectiveFrom == null || effectiveTo.isAfter(effectiveFrom);
    }

    @Data
    public static class EntitlementItemRequest {

        @NotBlank
        private String bucketCode;

        @NotNull
        @Min(0)
        private Long grantAmountMicros;

        @NotBlank
        private String grantType;

        private Map<String, Object> metadata;
    }
}
