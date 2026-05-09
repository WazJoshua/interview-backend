package com.josh.interviewj.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdminReviewBillingRefundRequest {

    @NotBlank
    private String decision;

    private String comment;
}
