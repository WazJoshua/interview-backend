package com.josh.interviewj.user.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.auth.support.PasswordEnvelopeDecryptor;
import com.josh.interviewj.auth.support.PasswordStrengthValidator;
import com.josh.interviewj.common.enums.ContentLocale;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.user.dto.request.ChangePasswordRequest;
import com.josh.interviewj.user.dto.request.UserUpdateRequest;
import com.josh.interviewj.user.dto.response.AvatarUploadResponse;
import com.josh.interviewj.user.dto.response.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.HashSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * User profile, password, and avatar operations.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private static final long MAX_AVATAR_SIZE_BYTES = 2L * 1024 * 1024;
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+()\\-\\s]{6,20}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AvatarStorageService avatarStorageService;
    private final PasswordEnvelopeDecryptor passwordEnvelopeDecryptor;
    private final PasswordStrengthValidator passwordStrengthValidator;
    private final UserAccessService userAccessService;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID targetUserId, String currentUsername) {
        return mapToUserProfileResponse(userAccessService.requireVisibleUser(targetUserId, currentUsername));
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID targetUserId, String currentUsername, UserUpdateRequest request) {
        User targetUser = userAccessService.requireVisibleUser(targetUserId, currentUsername);
        UserUpdateRequest safeRequest = request == null ? new UserUpdateRequest() : request;

        applyNickname(targetUser, safeRequest.getNickname());
        applyPhone(targetUser, safeRequest.getPhone());
        applyLocale(targetUser, safeRequest.getLocale());
        applyTimezone(targetUser, safeRequest.getTimezone());

        User savedUser = userRepository.save(targetUser);
        return mapToUserProfileResponse(savedUser);
    }

    @Transactional
    public void changePassword(UUID targetUserId, String currentUsername, ChangePasswordRequest request) {
        User targetUser = userAccessService.requireVisibleUser(targetUserId, currentUsername);
        HashSet<String> requestNonces = new HashSet<>();
        String oldPassword = passwordEnvelopeDecryptor.decrypt(request.getOldPasswordEnvelope(), "oldPasswordEnvelope", requestNonces);
        passwordStrengthValidator.validatePresentPassword(oldPassword, "oldPasswordEnvelope");
        String newPassword = passwordEnvelopeDecryptor.decrypt(request.getNewPasswordEnvelope(), "newPasswordEnvelope", requestNonces);
        passwordStrengthValidator.validateStrongPassword(newPassword, "newPasswordEnvelope");
        if (!passwordEncoder.matches(oldPassword, targetUser.getPassword())) {
            throw new BusinessException(ErrorCode.AUTH_001, "Invalid username or password");
        }

        targetUser.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(targetUser);
    }

    @Transactional
    public AvatarUploadResponse uploadAvatar(UUID targetUserId, String currentUsername, MultipartFile file) {
        User targetUser = userAccessService.requireVisibleUser(targetUserId, currentUsername);
        validateAvatarFile(file);

        String previousAvatarUrl = targetUser.getAvatarUrl();
        String avatarUrl = avatarStorageService.store(file);
        targetUser.setAvatarUrl(avatarUrl);
        userRepository.save(targetUser);

        if (previousAvatarUrl != null && !previousAvatarUrl.isBlank() && !previousAvatarUrl.equals(avatarUrl)) {
            try {
                avatarStorageService.delete(previousAvatarUrl);
            } catch (BusinessException ex) {
                log.warn("旧头像删除失败: userId={}, avatarUrl={}", targetUserId, previousAvatarUrl);
            }
        }

        return AvatarUploadResponse.builder()
                .avatarUrl(avatarUrl)
                .build();
    }

    private UserProfileResponse mapToUserProfileResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getExternalId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .phone(user.getPhone())
                .locale(ContentLocale.normalizeOrDefault(user.getLocale()))
                .timezone(user.getTimezone())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private void applyNickname(User user, JsonNullable<String> nickname) {
        if (nickname == null || !nickname.isDefined()) {
            return;
        }
        String value = nickname.orElse(null);
        if (value == null) {
            user.setNickname(null);
            return;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new BusinessException(ErrorCode.USER_004, "Nickname must be between 1 and 100 characters");
        }
        user.setNickname(trimmed);
    }

    private void applyPhone(User user, JsonNullable<String> phone) {
        if (phone == null || !phone.isDefined()) {
            return;
        }
        String value = phone.orElse(null);
        if (value == null) {
            user.setPhone(null);
            return;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty() || !PHONE_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessException(ErrorCode.USER_004, "Phone format is invalid");
        }
        user.setPhone(trimmed);
    }

    private void applyLocale(User user, JsonNullable<String> localeField) {
        if (localeField == null || !localeField.isDefined()) {
            return;
        }

        String locale = localeField.orElse(null);
        if (locale == null) {
            throw new BusinessException(ErrorCode.USER_004, "Locale must not be null");
        }

        String trimmed = locale.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.USER_004, "Locale must not be blank");
        }
        if (!ContentLocale.isSupported(trimmed)) {
            throw new BusinessException(ErrorCode.USER_004, "Locale is not supported");
        }
        user.setLocale(trimmed);
    }

    private void applyTimezone(User user, JsonNullable<String> timezoneField) {
        if (timezoneField == null || !timezoneField.isDefined()) {
            return;
        }

        String timezone = timezoneField.orElse(null);
        if (timezone == null) {
            throw new BusinessException(ErrorCode.USER_004, "Timezone must not be null");
        }

        String trimmed = timezone.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.USER_004, "Timezone must not be blank");
        }

        try {
            ZoneId.of(trimmed);
        } catch (ZoneRulesException ex) {
            throw new BusinessException(ErrorCode.USER_004, "Timezone is invalid");
        }
        user.setTimezone(trimmed);
    }

    private void validateAvatarFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.USER_004, "Avatar file must not be empty");
        }
        if (file.getSize() > MAX_AVATAR_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.USER_004, "Avatar file exceeds 2MB limit");
        }

        String contentType = file.getContentType();
        if (!"image/jpeg".equalsIgnoreCase(contentType) && !"image/png".equalsIgnoreCase(contentType)) {
            throw new BusinessException(ErrorCode.USER_004, "Avatar file type is not supported");
        }
    }
}
