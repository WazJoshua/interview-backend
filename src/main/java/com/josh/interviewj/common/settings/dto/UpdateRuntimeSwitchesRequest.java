package com.josh.interviewj.common.settings.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateRuntimeSwitchesRequest {

    @NotNull
    private Long expectedRevision;

    @NotNull
    private Boolean paymentEnabled;

    private String paymentDisabledMessage;

    @NotNull
    private Boolean activationCodeEnabled;

    private String activationCodeDisabledMessage;
}
