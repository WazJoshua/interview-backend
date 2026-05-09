package com.josh.interviewj.common.settings.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RuntimeSwitchesAdminView {

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

    public static RuntimeSwitchesAdminView fromSnapshot(RuntimeSwitchesSnapshot snapshot) {
        return RuntimeSwitchesAdminView.builder()
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
