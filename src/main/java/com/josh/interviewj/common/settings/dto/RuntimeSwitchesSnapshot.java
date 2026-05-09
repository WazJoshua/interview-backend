package com.josh.interviewj.common.settings.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RuntimeSwitchesSnapshot {

    boolean ready;
    Long revision;
    CapabilitySnapshot payment;
    CapabilitySnapshot activationCode;

    @Value
    @Builder
    public static class CapabilitySnapshot {
        boolean enabled;
        String disabledMessage;
    }
}
