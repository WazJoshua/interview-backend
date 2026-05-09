package com.josh.interviewj.common.settings.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesAdminView;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesPublicView;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesSnapshot;
import com.josh.interviewj.common.settings.dto.UpdateRuntimeSwitchesRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeSwitchService {

    private final SystemSettingService systemSettingService;

    private final AtomicReference<RuntimeSwitchesSnapshot> snapshotRef = new AtomicReference<>();

    @PostConstruct
    public void initSnapshot() {
        RuntimeSwitchesSnapshot degradedSnapshot = systemSettingService.degradedSnapshot();
        snapshotRef.set(degradedSnapshot);
        try {
            snapshotRef.set(systemSettingService.loadLatestSnapshot());
        } catch (RuntimeException exception) {
            log.error("runtime_switches_initial_load_failed message={}", exception.getMessage());
        }
    }

    public RuntimeSwitchesSnapshot getLatestForGuard() {
        return systemSettingService.loadLatestSnapshot();
    }

    public void requirePaymentEnabled() {
        requireCapabilityEnabled(
                "runtime_switch_payment_guard_failed",
                RuntimeSwitchesSnapshot::getPayment,
                ErrorCode.PAYMENT_006,
                systemSettingService.defaultPaymentDisabledMessage()
        );
    }

    public void requireActivationCodeEnabled() {
        requireCapabilityEnabled(
                "runtime_switch_activation_code_guard_failed",
                RuntimeSwitchesSnapshot::getActivationCode,
                ErrorCode.BILLING_ACTIVATION_004,
                systemSettingService.defaultActivationCodeDisabledMessage()
        );
    }

    public RuntimeSwitchesSnapshot getCachedSnapshot() {
        return currentSnapshot();
    }

    public RuntimeSwitchesAdminView getAdminView() {
        RuntimeSwitchesSnapshot latest = systemSettingService.loadLatestSnapshot();
        snapshotRef.set(latest);
        return RuntimeSwitchesAdminView.fromSnapshot(latest);
    }

    public RuntimeSwitchesPublicView getPublicView() {
        return RuntimeSwitchesPublicView.fromSnapshot(currentSnapshot());
    }

    public RuntimeSwitchesSnapshot refreshSnapshotIfRevisionChanged() {
        RuntimeSwitchesSnapshot current = currentSnapshot();
        try {
            Long latestRevision = systemSettingService.getCurrentRevision();
            if (current.isReady() && Objects.equals(current.getRevision(), latestRevision)) {
                return current;
            }
            RuntimeSwitchesSnapshot refreshed = systemSettingService.loadLatestSnapshot();
            snapshotRef.set(refreshed);
            return refreshed;
        } catch (RuntimeException exception) {
            log.error("runtime_switches_refresh_failed message={}", exception.getMessage(), exception);
            return current;
        }
    }

    public RuntimeSwitchesSnapshot updateSwitches(UpdateRuntimeSwitchesRequest request, Long actorUserId) {
        RuntimeSwitchesSnapshot updated = systemSettingService.updateSwitches(request, actorUserId);
        snapshotRef.set(updated);
        return updated;
    }

    private RuntimeSwitchesSnapshot currentSnapshot() {
        RuntimeSwitchesSnapshot current = snapshotRef.get();
        if (current != null) {
            return current;
        }
        RuntimeSwitchesSnapshot degraded = systemSettingService.degradedSnapshot();
        snapshotRef.compareAndSet(null, degraded);
        return snapshotRef.get();
    }

    private void requireCapabilityEnabled(
            String logEvent,
            Function<RuntimeSwitchesSnapshot, RuntimeSwitchesSnapshot.CapabilitySnapshot> capabilitySelector,
            String errorCode,
            String defaultDisabledMessage
    ) {
        RuntimeSwitchesSnapshot snapshot;
        try {
            snapshot = getLatestForGuard();
        } catch (DataAccessException | IllegalStateException exception) {
            log.error("{} message={}", logEvent, exception.getMessage(), exception);
            throw new BusinessException(errorCode, defaultDisabledMessage);
        }

        RuntimeSwitchesSnapshot.CapabilitySnapshot capability = capabilitySelector.apply(snapshot);
        if (capability == null || !capability.isEnabled()) {
            String disabledMessage = capability == null ? null : capability.getDisabledMessage();
            throw new BusinessException(
                    errorCode,
                    disabledMessage == null || disabledMessage.isBlank() ? defaultDisabledMessage : disabledMessage
            );
        }
    }
}
