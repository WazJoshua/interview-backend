package com.josh.interviewj.usage.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;

@Data
public class UpdateUserCreditPolicyRequest {

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime effectiveFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime effectiveTo;

    @NotNull
    @PositiveOrZero
    private Long resumeCreditsLimitMicros;

    @NotNull
    @PositiveOrZero
    private Long kbQueryCreditsLimitMicros;

    @NotNull
    @PositiveOrZero
    private Long kbIngestionCreditsLimitMicros;

    @NotNull
    @PositiveOrZero
    private Long interviewCreditsLimitMicros;

    @AssertTrue(message = "effectiveTo must be later than effectiveFrom")
    public boolean isValidEffectiveRange() {
        return effectiveTo == null || effectiveFrom == null || effectiveTo.isAfter(effectiveFrom);
    }
}
