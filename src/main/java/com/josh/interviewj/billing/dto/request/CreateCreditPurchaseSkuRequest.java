package com.josh.interviewj.billing.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

@Data
public class CreateCreditPurchaseSkuRequest {

    @NotBlank
    private String skuCode;

    @NotBlank
    private String displayName;

    private Boolean active = Boolean.TRUE;

    private Map<String, Object> metadata;

    @Valid
    @NotNull
    private VersionRequest initialVersion;

    @Data
    public static class VersionRequest {

        @NotNull
        @Min(1)
        private Integer versionNo;

        @NotNull
        @Min(1)
        private Long creditsAmountMicros;

        @NotNull
        @DecimalMin(value = "0.000001")
        private BigDecimal amount;

        @NotBlank
        private String currency;

        private Boolean saleEnabled = Boolean.TRUE;

        @NotNull
        private OffsetDateTime effectiveFrom;

        private OffsetDateTime effectiveTo;

        private Map<String, Object> metadata;

        @AssertTrue(message = "effectiveTo must be after effectiveFrom")
        @JsonIgnore
        public boolean isEffectiveRangeValid() {
            return effectiveTo == null || effectiveFrom == null || effectiveTo.isAfter(effectiveFrom);
        }
    }
}
