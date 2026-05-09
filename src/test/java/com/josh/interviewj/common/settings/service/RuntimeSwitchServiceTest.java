package com.josh.interviewj.common.settings.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesAdminView;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesSnapshot;
import com.josh.interviewj.common.settings.dto.UpdateRuntimeSwitchesRequest;
import com.josh.interviewj.common.settings.model.SystemSetting;
import com.josh.interviewj.common.settings.model.SystemSettingKey;
import com.josh.interviewj.common.settings.model.SystemSettingRevision;
import com.josh.interviewj.common.settings.repository.SystemSettingRepository;
import com.josh.interviewj.common.settings.repository.SystemSettingRevisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeSwitchServiceTest {

    @Mock
    private SystemSettingRepository systemSettingRepository;

    @Mock
    private SystemSettingRevisionRepository systemSettingRevisionRepository;

    @Mock
    private SystemSettingService systemSettingService;

    private SystemSettingService realSystemSettingService;

    @BeforeEach
    void setUp() {
        realSystemSettingService = new SystemSettingService(systemSettingRepository, systemSettingRevisionRepository);
    }

    @Test
    void loadLatestSnapshot_UsesDefaultsWhenSettingsNotPersisted() {
        when(systemSettingRevisionRepository.findById("GLOBAL"))
                .thenReturn(Optional.of(SystemSettingRevision.builder()
                        .singletonKey("GLOBAL")
                        .currentRevision(7L)
                        .build()));
        when(systemSettingRepository.findBySettingKeyIn(anyCollection())).thenReturn(List.of());

        RuntimeSwitchesSnapshot snapshot = realSystemSettingService.loadLatestSnapshot();

        assertThat(snapshot.isReady()).isTrue();
        assertThat(snapshot.getRevision()).isEqualTo(7L);
        assertThat(snapshot.getPayment().isEnabled()).isTrue();
        assertThat(snapshot.getPayment().getDisabledMessage())
                .isEqualTo("Payment capability is temporarily unavailable");
        assertThat(snapshot.getActivationCode().isEnabled()).isTrue();
        assertThat(snapshot.getActivationCode().getDisabledMessage())
                .isEqualTo("Activation code capability is temporarily unavailable");
    }

    @Test
    void loadLatestSnapshot_UsesPersistedValuesAndTrimsDisabledMessages() {
        when(systemSettingRevisionRepository.findById("GLOBAL"))
                .thenReturn(Optional.of(SystemSettingRevision.builder()
                        .singletonKey("GLOBAL")
                        .currentRevision(9L)
                        .build()));
        when(systemSettingRepository.findBySettingKeyIn(anyCollection())).thenReturn(List.of(
                SystemSetting.builder()
                        .settingKey(SystemSettingKey.PAYMENT_ENABLED)
                        .valueType("BOOLEAN")
                        .valueText("false")
                        .build(),
                SystemSetting.builder()
                        .settingKey(SystemSettingKey.PAYMENT_DISABLED_MESSAGE)
                        .valueType("STRING")
                        .valueText("  payment disabled by admin  ")
                        .build(),
                SystemSetting.builder()
                        .settingKey(SystemSettingKey.ACTIVATION_CODE_ENABLED)
                        .valueType("BOOLEAN")
                        .valueText("TRUE")
                        .build(),
                SystemSetting.builder()
                        .settingKey(SystemSettingKey.ACTIVATION_CODE_DISABLED_MESSAGE)
                        .valueType("STRING")
                        .valueText("   ")
                        .build()
        ));

        RuntimeSwitchesSnapshot snapshot = realSystemSettingService.loadLatestSnapshot();

        assertThat(snapshot.getRevision()).isEqualTo(9L);
        assertThat(snapshot.getPayment().isEnabled()).isFalse();
        assertThat(snapshot.getPayment().getDisabledMessage()).isEqualTo("payment disabled by admin");
        assertThat(snapshot.getActivationCode().isEnabled()).isTrue();
        assertThat(snapshot.getActivationCode().getDisabledMessage())
                .isEqualTo("Activation code capability is temporarily unavailable");
    }

    @Test
    void loadLatestSnapshot_WhenRevisionRowMissing_ThrowsIllegalStateException() {
        when(systemSettingRevisionRepository.findById("GLOBAL")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> realSystemSettingService.loadLatestSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("System setting revision row is missing");
    }

    @Test
    void loadLatestSnapshot_WhenBooleanValueTypeInvalid_ThrowsIllegalStateException() {
        when(systemSettingRevisionRepository.findById("GLOBAL"))
                .thenReturn(Optional.of(SystemSettingRevision.builder()
                        .singletonKey("GLOBAL")
                        .currentRevision(9L)
                        .build()));
        when(systemSettingRepository.findBySettingKeyIn(anyCollection())).thenReturn(List.of(
                SystemSetting.builder()
                        .settingKey(SystemSettingKey.PAYMENT_ENABLED)
                        .valueType("STRING")
                        .valueText("false")
                        .build()
        ));

        assertThatThrownBy(() -> realSystemSettingService.loadLatestSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid setting value type for PAYMENT_ENABLED");
    }

    @Test
    void loadLatestSnapshot_WhenBooleanValueInvalid_ThrowsIllegalStateException() {
        when(systemSettingRevisionRepository.findById("GLOBAL"))
                .thenReturn(Optional.of(SystemSettingRevision.builder()
                        .singletonKey("GLOBAL")
                        .currentRevision(9L)
                        .build()));
        when(systemSettingRepository.findBySettingKeyIn(anyCollection())).thenReturn(List.of(
                SystemSetting.builder()
                        .settingKey(SystemSettingKey.PAYMENT_ENABLED)
                        .valueType("BOOLEAN")
                        .valueText("not-a-boolean")
                        .build()
        ));

        assertThatThrownBy(() -> realSystemSettingService.loadLatestSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid boolean value for PAYMENT_ENABLED");
    }

    @Test
    void updateSwitches_IncrementsRevisionAndPersistsAllControlledKeys() {
        when(systemSettingRevisionRepository.bumpRevisionIfExpected("GLOBAL", 5L)).thenReturn(1);
        when(systemSettingRepository.findBySettingKeyIn(anyCollection())).thenReturn(List.of());
        when(systemSettingRepository.save(any(SystemSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateRuntimeSwitchesRequest request = new UpdateRuntimeSwitchesRequest();
        request.setExpectedRevision(5L);
        request.setPaymentEnabled(false);
        request.setPaymentDisabledMessage("payment maintenance");
        request.setActivationCodeEnabled(true);
        request.setActivationCodeDisabledMessage("   ");

        RuntimeSwitchesSnapshot updated = realSystemSettingService.updateSwitches(request, 101L);

        assertThat(updated.isReady()).isTrue();
        assertThat(updated.getRevision()).isEqualTo(6L);
        assertThat(updated.getPayment().isEnabled()).isFalse();
        assertThat(updated.getPayment().getDisabledMessage()).isEqualTo("payment maintenance");
        assertThat(updated.getActivationCode().isEnabled()).isTrue();
        assertThat(updated.getActivationCode().getDisabledMessage())
                .isEqualTo("Activation code capability is temporarily unavailable");

        verify(systemSettingRevisionRepository).bumpRevisionIfExpected("GLOBAL", 5L);
        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(systemSettingRepository, times(4)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(SystemSetting::getSettingKey)
                .containsExactlyInAnyOrder(
                        SystemSettingKey.PAYMENT_ENABLED,
                        SystemSettingKey.PAYMENT_DISABLED_MESSAGE,
                        SystemSettingKey.ACTIVATION_CODE_ENABLED,
                        SystemSettingKey.ACTIVATION_CODE_DISABLED_MESSAGE
                );
    }

    @Test
    void updateSwitches_WhenRequestMissing_ThrowsValidationError() {
        assertThatThrownBy(() -> realSystemSettingService.updateSwitches(null, 101L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void updateSwitches_WhenExpectedRevisionMissing_ThrowsValidationError() {
        UpdateRuntimeSwitchesRequest request = new UpdateRuntimeSwitchesRequest();
        request.setPaymentEnabled(true);
        request.setActivationCodeEnabled(true);

        assertThatThrownBy(() -> realSystemSettingService.updateSwitches(request, 101L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_ERROR");
        verify(systemSettingRevisionRepository, never()).bumpRevisionIfExpected(any(), any());
    }

    @Test
    void updateSwitches_WhenCapabilityFlagsMissing_ThrowsValidationError() {
        UpdateRuntimeSwitchesRequest request = new UpdateRuntimeSwitchesRequest();
        request.setExpectedRevision(3L);
        request.setPaymentEnabled(null);
        request.setActivationCodeEnabled(true);

        assertThatThrownBy(() -> realSystemSettingService.updateSwitches(request, 101L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_ERROR");
        verify(systemSettingRevisionRepository, never()).bumpRevisionIfExpected(any(), any());
    }

    @Test
    void updateSwitches_WhenExpectedRevisionMismatch_ThrowsConflict() {
        when(systemSettingRevisionRepository.bumpRevisionIfExpected("GLOBAL", 2L)).thenReturn(0);

        UpdateRuntimeSwitchesRequest request = new UpdateRuntimeSwitchesRequest();
        request.setExpectedRevision(2L);
        request.setPaymentEnabled(true);
        request.setPaymentDisabledMessage("payment");
        request.setActivationCodeEnabled(true);
        request.setActivationCodeDisabledMessage("activation");

        assertThatThrownBy(() -> realSystemSettingService.updateSwitches(request, 101L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("ADMIN_BILLING_005");
    }

    @Test
    void updateSwitches_WhenCapabilityDisabledAndMessageBlank_ThrowsValidationError() {
        UpdateRuntimeSwitchesRequest request = new UpdateRuntimeSwitchesRequest();
        request.setExpectedRevision(3L);
        request.setPaymentEnabled(false);
        request.setPaymentDisabledMessage("   ");
        request.setActivationCodeEnabled(true);
        request.setActivationCodeDisabledMessage("activation");

        assertThatThrownBy(() -> realSystemSettingService.updateSwitches(request, 101L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_ERROR");
        verify(systemSettingRevisionRepository, never()).bumpRevisionIfExpected(any(), any());
    }

    @Test
    void initSnapshot_WhenFirstLoadFails_KeepsDegradedReadyFalseRevisionNull() {
        RuntimeSwitchesSnapshot degraded = degradedSnapshot();
        when(systemSettingService.degradedSnapshot()).thenReturn(degraded);
        when(systemSettingService.loadLatestSnapshot()).thenThrow(new IllegalStateException("db down"));
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);

        runtimeSwitchService.initSnapshot();

        RuntimeSwitchesSnapshot snapshot = runtimeSwitchService.getCachedSnapshot();
        assertThat(snapshot.isReady()).isFalse();
        assertThat(snapshot.getRevision()).isNull();
    }

    @Test
    void initSnapshot_WhenFirstLoadSucceeds_SetsReadyTrueAndLatestRevision() {
        RuntimeSwitchesSnapshot degraded = degradedSnapshot();
        RuntimeSwitchesSnapshot latest = readySnapshot(12L, false, "pay off", true, "act msg");
        when(systemSettingService.degradedSnapshot()).thenReturn(degraded);
        when(systemSettingService.loadLatestSnapshot()).thenReturn(latest);
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);

        runtimeSwitchService.initSnapshot();

        RuntimeSwitchesSnapshot snapshot = runtimeSwitchService.getCachedSnapshot();
        assertThat(snapshot.isReady()).isTrue();
        assertThat(snapshot.getRevision()).isEqualTo(12L);
    }

    @Test
    void getCachedSnapshot_WhenInitNotCalled_UsesDegradedSnapshotLazily() {
        RuntimeSwitchesSnapshot degraded = degradedSnapshot();
        when(systemSettingService.degradedSnapshot()).thenReturn(degraded);
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);

        RuntimeSwitchesSnapshot cached = runtimeSwitchService.getCachedSnapshot();

        assertThat(cached).isEqualTo(degraded);
        verify(systemSettingService).degradedSnapshot();
        verify(systemSettingService, never()).loadLatestSnapshot();
    }

    @Test
    void getLatestForGuard_DelegatesToSystemSettingService() {
        RuntimeSwitchesSnapshot latest = readySnapshot(13L, false, "pay off", false, "act off");
        when(systemSettingService.loadLatestSnapshot()).thenReturn(latest);
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);

        RuntimeSwitchesSnapshot result = runtimeSwitchService.getLatestForGuard();

        assertThat(result).isEqualTo(latest);
        verify(systemSettingService).loadLatestSnapshot();
    }

    @Test
    void requirePaymentEnabled_WhenDisabled_ThrowsPayment006WithConfiguredMessage() {
        when(systemSettingService.loadLatestSnapshot()).thenReturn(readySnapshot(13L, false, "payment disabled", true, "act"));
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);

        assertThatThrownBy(runtimeSwitchService::requirePaymentEnabled)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_006);
                    assertThat(businessException.getMessage()).isEqualTo("payment disabled");
                });
    }

    @Test
    void requirePaymentEnabled_WhenSnapshotLoadFails_ThrowsPayment006WithDefaultMessage() {
        when(systemSettingService.loadLatestSnapshot()).thenThrow(new DataAccessResourceFailureException("db down"));
        when(systemSettingService.defaultPaymentDisabledMessage())
                .thenReturn("Payment capability is temporarily unavailable");
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);

        assertThatThrownBy(runtimeSwitchService::requirePaymentEnabled)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_006);
                    assertThat(businessException.getMessage()).isEqualTo("Payment capability is temporarily unavailable");
                });
    }

    @Test
    void requireActivationCodeEnabled_WhenCapabilityMissing_ThrowsDefaultMessage() {
        when(systemSettingService.loadLatestSnapshot()).thenReturn(RuntimeSwitchesSnapshot.builder()
                .ready(true)
                .revision(13L)
                .payment(RuntimeSwitchesSnapshot.CapabilitySnapshot.builder()
                        .enabled(true)
                        .disabledMessage("pay")
                        .build())
                .activationCode(null)
                .build());
        when(systemSettingService.defaultActivationCodeDisabledMessage())
                .thenReturn("Activation code capability is temporarily unavailable");
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);

        assertThatThrownBy(runtimeSwitchService::requireActivationCodeEnabled)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.BILLING_ACTIVATION_004);
                    assertThat(businessException.getMessage())
                            .isEqualTo("Activation code capability is temporarily unavailable");
                });
    }

    @Test
    void requireActivationCodeEnabled_WhenSnapshotLoadFails_ThrowsDefaultMessage() {
        when(systemSettingService.loadLatestSnapshot()).thenThrow(new IllegalStateException("missing revision"));
        when(systemSettingService.defaultActivationCodeDisabledMessage())
                .thenReturn("Activation code capability is temporarily unavailable");
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);

        assertThatThrownBy(runtimeSwitchService::requireActivationCodeEnabled)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.BILLING_ACTIVATION_004);
                    assertThat(businessException.getMessage())
                            .isEqualTo("Activation code capability is temporarily unavailable");
                });
    }

    @Test
    void getPublicView_ReturnsMappedCachedSnapshot() {
        RuntimeSwitchesSnapshot degraded = degradedSnapshot();
        RuntimeSwitchesSnapshot initial = readySnapshot(6L, false, "pay down", true, "act up");
        when(systemSettingService.degradedSnapshot()).thenReturn(degraded);
        when(systemSettingService.loadLatestSnapshot()).thenReturn(initial);
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);
        runtimeSwitchService.initSnapshot();

        assertThat(runtimeSwitchService.getPublicView().getRevision()).isEqualTo(6L);
        assertThat(runtimeSwitchService.getPublicView().getPayment().isEnabled()).isFalse();
    }

    @Test
    void refreshSnapshotIfRevisionChanged_WhenRevisionUnchanged_DoesNotReloadSnapshot() {
        RuntimeSwitchesSnapshot degraded = degradedSnapshot();
        RuntimeSwitchesSnapshot initial = readySnapshot(3L, true, "pay", true, "act");
        when(systemSettingService.degradedSnapshot()).thenReturn(degraded);
        when(systemSettingService.loadLatestSnapshot()).thenReturn(initial);
        when(systemSettingService.getCurrentRevision()).thenReturn(3L);
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);
        runtimeSwitchService.initSnapshot();

        RuntimeSwitchesSnapshot refreshed = runtimeSwitchService.refreshSnapshotIfRevisionChanged();

        assertThat(refreshed.getRevision()).isEqualTo(3L);
        verify(systemSettingService, times(1)).loadLatestSnapshot();
    }

    @Test
    void refreshSnapshotIfRevisionChanged_WhenRevisionChanged_ReloadsSnapshot() {
        RuntimeSwitchesSnapshot degraded = degradedSnapshot();
        RuntimeSwitchesSnapshot initial = readySnapshot(3L, true, "pay", true, "act");
        RuntimeSwitchesSnapshot updated = readySnapshot(4L, false, "pay off", true, "act");
        when(systemSettingService.degradedSnapshot()).thenReturn(degraded);
        when(systemSettingService.loadLatestSnapshot()).thenReturn(initial, updated);
        when(systemSettingService.getCurrentRevision()).thenReturn(4L);
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);
        runtimeSwitchService.initSnapshot();

        RuntimeSwitchesSnapshot refreshed = runtimeSwitchService.refreshSnapshotIfRevisionChanged();

        assertThat(refreshed.getRevision()).isEqualTo(4L);
        assertThat(runtimeSwitchService.getCachedSnapshot().getRevision()).isEqualTo(4L);
        verify(systemSettingService, times(2)).loadLatestSnapshot();
    }

    @Test
    void refreshSnapshotIfRevisionChanged_WhenCurrentSnapshotNotReady_AttemptsReload() {
        RuntimeSwitchesSnapshot degraded = degradedSnapshot();
        RuntimeSwitchesSnapshot refreshedSnapshot = readySnapshot(5L, false, "pay off", true, "act");
        when(systemSettingService.degradedSnapshot()).thenReturn(degraded);
        when(systemSettingService.loadLatestSnapshot()).thenReturn(refreshedSnapshot);
        when(systemSettingService.getCurrentRevision()).thenReturn(5L);
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);

        RuntimeSwitchesSnapshot refreshed = runtimeSwitchService.refreshSnapshotIfRevisionChanged();

        assertThat(refreshed).isEqualTo(refreshedSnapshot);
        assertThat(runtimeSwitchService.getCachedSnapshot()).isEqualTo(refreshedSnapshot);
        verify(systemSettingService).getCurrentRevision();
        verify(systemSettingService).loadLatestSnapshot();
    }

    @Test
    void refreshSnapshotIfRevisionChanged_WhenRefreshFails_KeepsCurrentSnapshot() {
        RuntimeSwitchesSnapshot degraded = degradedSnapshot();
        RuntimeSwitchesSnapshot initial = readySnapshot(3L, true, "pay", true, "act");
        when(systemSettingService.degradedSnapshot()).thenReturn(degraded);
        when(systemSettingService.loadLatestSnapshot()).thenReturn(initial);
        when(systemSettingService.getCurrentRevision()).thenThrow(new IllegalStateException("db down"));
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);
        runtimeSwitchService.initSnapshot();

        RuntimeSwitchesSnapshot refreshed = runtimeSwitchService.refreshSnapshotIfRevisionChanged();

        assertThat(refreshed).isEqualTo(initial);
        assertThat(runtimeSwitchService.getCachedSnapshot()).isEqualTo(initial);
    }

    @Test
    void updateSwitches_RefreshesCachedSnapshotImmediately() {
        RuntimeSwitchesSnapshot degraded = degradedSnapshot();
        RuntimeSwitchesSnapshot updated = readySnapshot(8L, false, "pay down", false, "act down");
        when(systemSettingService.degradedSnapshot()).thenReturn(degraded);
        when(systemSettingService.loadLatestSnapshot()).thenReturn(readySnapshot(7L, true, "pay", true, "act"));
        when(systemSettingService.updateSwitches(any(UpdateRuntimeSwitchesRequest.class), eq(202L)))
                .thenReturn(updated);
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);
        runtimeSwitchService.initSnapshot();

        UpdateRuntimeSwitchesRequest request = new UpdateRuntimeSwitchesRequest();
        request.setExpectedRevision(7L);
        request.setPaymentEnabled(false);
        request.setPaymentDisabledMessage("pay down");
        request.setActivationCodeEnabled(false);
        request.setActivationCodeDisabledMessage("act down");
        RuntimeSwitchesSnapshot result = runtimeSwitchService.updateSwitches(request, 202L);

        assertThat(result.getRevision()).isEqualTo(8L);
        assertThat(runtimeSwitchService.getCachedSnapshot().getRevision()).isEqualTo(8L);
    }

    @Test
    void getAdminView_ReadsLatestSnapshotFromDatabaseAndUpdatesCache() {
        RuntimeSwitchesSnapshot degraded = degradedSnapshot();
        RuntimeSwitchesSnapshot initial = readySnapshot(7L, true, "pay", true, "act");
        RuntimeSwitchesSnapshot latest = readySnapshot(9L, false, "payment down", true, "act");
        when(systemSettingService.degradedSnapshot()).thenReturn(degraded);
        when(systemSettingService.loadLatestSnapshot()).thenReturn(initial, latest);
        RuntimeSwitchService runtimeSwitchService = new RuntimeSwitchService(systemSettingService);
        runtimeSwitchService.initSnapshot();

        RuntimeSwitchesAdminView adminView = runtimeSwitchService.getAdminView();

        assertThat(adminView.getRevision()).isEqualTo(9L);
        assertThat(adminView.isReady()).isTrue();
        assertThat(adminView.getPayment().isEnabled()).isFalse();
        assertThat(runtimeSwitchService.getCachedSnapshot().getRevision()).isEqualTo(9L);
        verify(systemSettingService, times(2)).loadLatestSnapshot();
    }

    private RuntimeSwitchesSnapshot degradedSnapshot() {
        return RuntimeSwitchesSnapshot.builder()
                .ready(false)
                .revision(null)
                .payment(RuntimeSwitchesSnapshot.CapabilitySnapshot.builder()
                        .enabled(true)
                        .disabledMessage("Payment capability is temporarily unavailable")
                        .build())
                .activationCode(RuntimeSwitchesSnapshot.CapabilitySnapshot.builder()
                        .enabled(true)
                        .disabledMessage("Activation code capability is temporarily unavailable")
                        .build())
                .build();
    }

    private RuntimeSwitchesSnapshot readySnapshot(
            Long revision,
            boolean paymentEnabled,
            String paymentMessage,
            boolean activationEnabled,
            String activationMessage
    ) {
        return RuntimeSwitchesSnapshot.builder()
                .ready(true)
                .revision(revision)
                .payment(RuntimeSwitchesSnapshot.CapabilitySnapshot.builder()
                        .enabled(paymentEnabled)
                        .disabledMessage(paymentMessage)
                        .build())
                .activationCode(RuntimeSwitchesSnapshot.CapabilitySnapshot.builder()
                        .enabled(activationEnabled)
                        .disabledMessage(activationMessage)
                        .build())
                .build();
    }
}
