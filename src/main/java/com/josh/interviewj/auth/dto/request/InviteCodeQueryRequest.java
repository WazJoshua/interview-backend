package com.josh.interviewj.auth.dto.request;

import com.josh.interviewj.auth.model.InviteCodeStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class InviteCodeQueryRequest {

    @Min(value = 0, message = "Page must be greater than or equal to 0")
    private int page = 0;

    @Min(value = 1, message = "Size must be greater than or equal to 1")
    @Max(value = 100, message = "Size must be less than or equal to 100")
    private int size = 20;

    private InviteCodeStatus status;
}
