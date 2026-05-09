package com.josh.interviewj.usage.dto.request;

import com.josh.interviewj.usage.model.UsageHistoryCategory;
import com.josh.interviewj.usage.model.UsageHistorySourceType;
import com.josh.interviewj.usage.model.UsageHistoryWindowType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;

@Data
public class UserUsageHistoryQuery {

    private UsageHistoryWindowType windowType = UsageHistoryWindowType.DEFAULT;

    private UsageHistoryCategory category;

    private UsageHistorySourceType sourceType;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime to;

    @Min(0)
    private Integer page = 0;

    @Min(1)
    @Max(50)
    private Integer size = 20;

    @AssertTrue(message = "CUSTOM windowType requires both from and to")
    public boolean isCustomWindowComplete() {
        if (resolvedWindowType() != UsageHistoryWindowType.CUSTOM) {
            return true;
        }
        return from != null && to != null;
    }

    @AssertTrue(message = "from and to are only allowed when windowType is CUSTOM")
    public boolean isCustomWindowExclusive() {
        if (resolvedWindowType() == UsageHistoryWindowType.CUSTOM) {
            return true;
        }
        return from == null && to == null;
    }

    @AssertTrue(message = "from must be earlier than to")
    public boolean isValidRange() {
        if (from == null || to == null) {
            return true;
        }
        return from.isBefore(to);
    }

    public UsageHistoryWindowType resolvedWindowType() {
        return windowType == null ? UsageHistoryWindowType.DEFAULT : windowType;
    }
}
