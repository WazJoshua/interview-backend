package com.josh.interviewj.common.settings.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesSnapshot;
import com.josh.interviewj.common.settings.dto.UpdateRuntimeSwitchesRequest;
import com.josh.interviewj.common.settings.model.SystemSetting;
import com.josh.interviewj.common.settings.model.SystemSettingKey;
import com.josh.interviewj.common.settings.model.SystemSettingRevision;
import com.josh.interviewj.common.settings.repository.SystemSettingRepository;
import com.josh.interviewj.common.settings.repository.SystemSettingRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private static final String SINGLETON_KEY_GLOBAL = "GLOBAL";
    private static final String VALUE_TYPE_BOOLEAN = "BOOLEAN";
    private static final String VALUE_TYPE_STRING = "STRING";
    private static final Set<SystemSettingKey> CONTROLLED_KEYS = EnumSet.of(
            SystemSettingKey.PAYMENT_ENABLED,
            SystemSettingKey.PAYMENT_DISABLED_MESSAGE,
            SystemSettingKey.ACTIVATION_CODE_ENABLED,
            SystemSettingKey.ACTIVATION_CODE_DISABLED_MESSAGE
    );

    private static final String DEFAULT_PAYMENT_DISABLED_MESSAGE = "Payment capability is temporarily unavailable";
    private static final String DEFAULT_ACTIVATION_CODE_DISABLED_MESSAGE = "Activation code capability is temporarily unavailable";

    private final SystemSettingRepository systemSettingRepository;
    private final SystemSettingRevisionRepository systemSettingRevisionRepository;

    @Transactional(readOnly = true)
    public RuntimeSwitchesSnapshot loadLatestSnapshot() {
        Long revision = getCurrentRevision();
        Map<SystemSettingKey, SystemSetting> settingByKey = loadControlledSettings();

        boolean paymentEnabled = readBoolean(settingByKey.get(SystemSettingKey.PAYMENT_ENABLED), true);
        String paymentDisabledMessage = readString(
                settingByKey.get(SystemSettingKey.PAYMENT_DISABLED_MESSAGE),
                DEFAULT_PAYMENT_DISABLED_MESSAGE
        );
        boolean activationCodeEnabled = readBoolean(settingByKey.get(SystemSettingKey.ACTIVATION_CODE_ENABLED), true);
        String activationCodeDisabledMessage = readString(
                settingByKey.get(SystemSettingKey.ACTIVATION_CODE_DISABLED_MESSAGE),
                DEFAULT_ACTIVATION_CODE_DISABLED_MESSAGE
        );

        return readySnapshot(revision, paymentEnabled, paymentDisabledMessage, activationCodeEnabled, activationCodeDisabledMessage);
    }

    @Transactional(readOnly = true)
    public Long getCurrentRevision() {
        return systemSettingRevisionRepository.findById(SINGLETON_KEY_GLOBAL)
                .map(SystemSettingRevision::getCurrentRevision)
                .orElseThrow(() -> new IllegalStateException("System setting revision row is missing"));
    }

    @Transactional
    public RuntimeSwitchesSnapshot updateSwitches(UpdateRuntimeSwitchesRequest request, Long actorUserId) {
        if (request == null) {
            throw new BusinessException("VALIDATION_ERROR", "Request body is required");
        }
        if (request.getExpectedRevision() == null) {
            throw new BusinessException("VALIDATION_ERROR", "expectedRevision is required");
        }
        if (request.getPaymentEnabled() == null || request.getActivationCodeEnabled() == null) {
            throw new BusinessException("VALIDATION_ERROR", "Both paymentEnabled and activationCodeEnabled are required");
        }

        String paymentDisabledMessage = normalizeMessage(request.getPaymentDisabledMessage());
        String activationCodeDisabledMessage = normalizeMessage(request.getActivationCodeDisabledMessage());
        validateDisabledMessage(request.getPaymentEnabled(), paymentDisabledMessage, "paymentDisabledMessage");
        validateDisabledMessage(request.getActivationCodeEnabled(), activationCodeDisabledMessage, "activationCodeDisabledMessage");

        int updatedRows = systemSettingRevisionRepository.bumpRevisionIfExpected(
                SINGLETON_KEY_GLOBAL,
                request.getExpectedRevision()
        );
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_005, "Runtime switches revision conflict");
        }

        Long newRevision = request.getExpectedRevision() + 1;
        Map<SystemSettingKey, SystemSetting> existingByKey = loadControlledSettings();
        saveBooleanSetting(existingByKey, SystemSettingKey.PAYMENT_ENABLED, request.getPaymentEnabled(), actorUserId);
        saveStringSetting(existingByKey, SystemSettingKey.PAYMENT_DISABLED_MESSAGE, paymentDisabledMessage, actorUserId);
        saveBooleanSetting(existingByKey, SystemSettingKey.ACTIVATION_CODE_ENABLED, request.getActivationCodeEnabled(), actorUserId);
        saveStringSetting(existingByKey, SystemSettingKey.ACTIVATION_CODE_DISABLED_MESSAGE, activationCodeDisabledMessage, actorUserId);

        String safePaymentMessage = paymentDisabledMessage.isBlank()
                ? DEFAULT_PAYMENT_DISABLED_MESSAGE
                : paymentDisabledMessage;
        String safeActivationMessage = activationCodeDisabledMessage.isBlank()
                ? DEFAULT_ACTIVATION_CODE_DISABLED_MESSAGE
                : activationCodeDisabledMessage;
        return readySnapshot(
                newRevision,
                request.getPaymentEnabled(),
                safePaymentMessage,
                request.getActivationCodeEnabled(),
                safeActivationMessage
        );
    }

    public RuntimeSwitchesSnapshot degradedSnapshot() {
        return RuntimeSwitchesSnapshot.builder()
                .ready(false)
                .revision(null)
                .payment(RuntimeSwitchesSnapshot.CapabilitySnapshot.builder()
                        .enabled(true)
                        .disabledMessage(DEFAULT_PAYMENT_DISABLED_MESSAGE)
                        .build())
                .activationCode(RuntimeSwitchesSnapshot.CapabilitySnapshot.builder()
                        .enabled(true)
                        .disabledMessage(DEFAULT_ACTIVATION_CODE_DISABLED_MESSAGE)
                        .build())
                .build();
    }

    public String defaultPaymentDisabledMessage() {
        return DEFAULT_PAYMENT_DISABLED_MESSAGE;
    }

    public String defaultActivationCodeDisabledMessage() {
        return DEFAULT_ACTIVATION_CODE_DISABLED_MESSAGE;
    }

    private RuntimeSwitchesSnapshot readySnapshot(
            Long revision,
            boolean paymentEnabled,
            String paymentDisabledMessage,
            boolean activationCodeEnabled,
            String activationCodeDisabledMessage
    ) {
        return RuntimeSwitchesSnapshot.builder()
                .ready(true)
                .revision(revision)
                .payment(RuntimeSwitchesSnapshot.CapabilitySnapshot.builder()
                        .enabled(paymentEnabled)
                        .disabledMessage(paymentDisabledMessage)
                        .build())
                .activationCode(RuntimeSwitchesSnapshot.CapabilitySnapshot.builder()
                        .enabled(activationCodeEnabled)
                        .disabledMessage(activationCodeDisabledMessage)
                        .build())
                .build();
    }

    private Map<SystemSettingKey, SystemSetting> loadControlledSettings() {
        List<SystemSetting> settings = systemSettingRepository.findBySettingKeyIn(CONTROLLED_KEYS);
        Map<SystemSettingKey, SystemSetting> settingByKey = new EnumMap<>(SystemSettingKey.class);
        for (SystemSetting setting : settings) {
            settingByKey.put(setting.getSettingKey(), setting);
        }
        return settingByKey;
    }

    private boolean readBoolean(SystemSetting setting, boolean defaultValue) {
        if (setting == null) {
            return defaultValue;
        }
        if (!VALUE_TYPE_BOOLEAN.equals(setting.getValueType())) {
            throw new IllegalStateException("Invalid setting value type for " + setting.getSettingKey());
        }
        String value = normalizeMessage(setting.getValueText());
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException("Invalid boolean value for " + setting.getSettingKey());
    }

    private String readString(SystemSetting setting, String defaultValue) {
        if (setting == null) {
            return defaultValue;
        }
        if (!VALUE_TYPE_STRING.equals(setting.getValueType())) {
            throw new IllegalStateException("Invalid setting value type for " + setting.getSettingKey());
        }
        String value = normalizeMessage(setting.getValueText());
        return value.isBlank() ? defaultValue : value;
    }

    private void saveBooleanSetting(
            Map<SystemSettingKey, SystemSetting> existingByKey,
            SystemSettingKey key,
            boolean value,
            Long actorUserId
    ) {
        SystemSetting setting = existingByKey.getOrDefault(key, new SystemSetting());
        setting.setSettingKey(key);
        setting.setValueType(VALUE_TYPE_BOOLEAN);
        setting.setValueText(Boolean.toString(value));
        setting.setUpdatedByUserId(actorUserId);
        systemSettingRepository.save(setting);
    }

    private void saveStringSetting(
            Map<SystemSettingKey, SystemSetting> existingByKey,
            SystemSettingKey key,
            String value,
            Long actorUserId
    ) {
        SystemSetting setting = existingByKey.getOrDefault(key, new SystemSetting());
        setting.setSettingKey(key);
        setting.setValueType(VALUE_TYPE_STRING);
        setting.setValueText(value);
        setting.setUpdatedByUserId(actorUserId);
        systemSettingRepository.save(setting);
    }

    private void validateDisabledMessage(boolean enabled, String message, String fieldName) {
        if (!enabled && message.isBlank()) {
            throw new BusinessException("VALIDATION_ERROR", fieldName + " is required when capability is disabled");
        }
    }

    private String normalizeMessage(String value) {
        return value == null ? "" : value.trim();
    }
}
