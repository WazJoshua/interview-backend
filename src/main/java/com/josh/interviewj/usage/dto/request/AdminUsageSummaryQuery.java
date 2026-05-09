package com.josh.interviewj.usage.dto.request;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;

@Data
public class AdminUsageSummaryQuery {

    private String dimension = "modelCode";

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime to;

    @AssertTrue(message = "from must be earlier than to")
    public boolean isValidRange() {
        return from == null || to == null || from.isBefore(to);
    }
}
