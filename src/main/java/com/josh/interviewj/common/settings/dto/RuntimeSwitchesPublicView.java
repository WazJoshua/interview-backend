package com.josh.interviewj.common.settings.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RuntimeSwitchesPublicView {

    boolean ready;
    Long revision;
    CapabilityView payment;
    CapabilityView activationCode;

    @Value
    @Builder
    public static class CapabilityView {
        boolean enabled;
        String disabledMessage;
    }

    public static RuntimeSwitchesPublicView fromSnapshot(RuntimeSwitchesSnapshot snapshot) {
        return RuntimeSwitchesPublicView.builder()
                .ready(snapshot.isReady())
                .revision(snapshot.getRevision())
                .payment(CapabilityView.builder()
                        .enabled(snapshot.getPayment().isEnabled())
                        .disabledMessage(snapshot.getPayment().getDisabledMessage())
                        .build())
                .activationCode(CapabilityView.builder()
                        .enabled(snapshot.getActivationCode().isEnabled())
                        .disabledMessage(snapshot.getActivationCode().getDisabledMessage())
                        .build())
                .build();
    }
}
