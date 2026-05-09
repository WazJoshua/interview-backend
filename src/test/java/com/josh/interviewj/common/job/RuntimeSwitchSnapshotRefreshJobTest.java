package com.josh.interviewj.common.job;

import com.josh.interviewj.common.settings.config.RuntimeSettingsProperties;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RuntimeSwitchSnapshotRefreshJobTest {

    private RuntimeSettingsProperties runtimeSettingsProperties;
    private RuntimeSwitchService runtimeSwitchService;
    private RuntimeSwitchSnapshotRefreshJob job;

    @BeforeEach
    void setUp() {
        runtimeSettingsProperties = new RuntimeSettingsProperties();
        runtimeSwitchService = mock(RuntimeSwitchService.class);
        job = new RuntimeSwitchSnapshotRefreshJob(runtimeSettingsProperties, runtimeSwitchService);
    }

    @Test
    void refreshSnapshot_WhenRefreshEnabled_DelegatesToRuntimeSwitchService() {
        runtimeSettingsProperties.getRefresh().setEnabled(true);

        job.refreshSnapshot();

        verify(runtimeSwitchService).refreshSnapshotIfRevisionChanged();
    }

    @Test
    void refreshSnapshot_WhenRefreshDisabled_DoesNotCallRuntimeSwitchService() {
        runtimeSettingsProperties.getRefresh().setEnabled(false);

        job.refreshSnapshot();

        verify(runtimeSwitchService, never()).refreshSnapshotIfRevisionChanged();
    }

    @Test
    void refreshSnapshot_WhenRuntimeSwitchServiceThrows_DoesNotPropagateException() {
        runtimeSettingsProperties.getRefresh().setEnabled(true);
        doThrow(new IllegalStateException("db unavailable"))
                .when(runtimeSwitchService)
                .refreshSnapshotIfRevisionChanged();

        job.refreshSnapshot();

        verify(runtimeSwitchService).refreshSnapshotIfRevisionChanged();
    }
}
