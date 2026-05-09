package com.josh.interviewj.usage.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AdminUsageEventsQuery {

    private UUID userId;
    private String purpose;
    private String usageFamily;
    private String chargeBucket;
    private String chargeStatus;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime to;

    @Min(0)
    private Integer page = 0;

    @Min(1)
    @Max(100)
    private Integer size = 20;

    @AssertTrue(message = "from must be earlier than to")
    public boolean isValidRange() {
        return from == null || to == null || from.isBefore(to);
    }
}
