package com.josh.interviewj.billing.activation.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ActivationCodeBatchResponse {

    private UUID batchId;
    private int count;
    private List<String> codes;
}
