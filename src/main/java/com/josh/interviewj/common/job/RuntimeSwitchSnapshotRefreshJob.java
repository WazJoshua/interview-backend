package com.josh.interviewj.common.job;

import com.josh.interviewj.common.settings.config.RuntimeSettingsProperties;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "app.runtime-settings.refresh.enabled", havingValue = "true", matchIfMissing = true)
public class RuntimeSwitchSnapshotRefreshJob {

    private final RuntimeSettingsProperties runtimeSettingsProperties;
    private final RuntimeSwitchService runtimeSwitchService;

    @Scheduled(fixedDelayString = "#{@runtimeSettingsProperties.refresh.pollInterval.toMillis()}")
    public void refreshSnapshot() {
        if (!runtimeSettingsProperties.getRefresh().isEnabled()) {
            return;
        }
        try {
            runtimeSwitchService.refreshSnapshotIfRevisionChanged();
        } catch (RuntimeException exception) {
            log.error("runtime_switches_refresh_job_failed message={}", exception.getMessage());
        }
    }
}
