package com.josh.interviewj.service;

import com.josh.interviewj.auth.dto.request.PasswordEnvelope;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.auth.support.PasswordEnvelopeDecryptor;
import com.josh.interviewj.auth.support.PasswordStrengthValidator;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.user.dto.request.ChangePasswordRequest;
import com.josh.interviewj.user.dto.request.UserUpdateRequest;
import com.josh.interviewj.user.dto.response.AvatarUploadResponse;
import com.josh.interviewj.user.dto.response.UserProfileResponse;
import com.josh.interviewj.user.service.AvatarStorageService;
import com.josh.interviewj.user.service.UserAccessService;
import com.josh.interviewj.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AvatarStorageService avatarStorageService;

    @Mock
    private PasswordEnvelopeDecryptor passwordEnvelopeDecryptor;

    @Mock
    private PasswordStrengthValidator passwordStrengthValidator;

    @Mock
    private UserAccessService userAccessService;

    @InjectMocks
    private UserService userService;

    private UUID selfUserId;
    private User currentUser;
    private User targetUser;

    @BeforeEach
    void setUp() {
        selfUserId = UUID.randomUUID();
        currentUser = User.builder()
                .id(1L)
                .externalId(selfUserId)
                .username("testuser")
                .email("test@example.com")
                .password("encoded-old-password")
                .nickname("测试用户")
                .phone("13800138000")
                .locale("zh-CN")
                .timezone("Asia/Shanghai")
                .createdAt(LocalDateTime.of(2026, 3, 13, 9, 0))
                .build();
        targetUser = currentUser;
    }

    @Test
    void getProfile_SelfAccess_ReturnsNormalizedLocale() {
        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);

        UserProfileResponse response = userService.getProfile(selfUserId, "testuser");

        assertNotNull(response);
        assertEquals(selfUserId, response.getId());
        assertEquals("zh-CN", response.getLocale());
    }

    @Test
    void getProfile_HistoricalNullLocale_ReturnsDefaultLocale() {
        targetUser.setLocale(null);
        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);

        UserProfileResponse response = userService.getProfile(selfUserId, "testuser");

        assertEquals("zh-CN", response.getLocale());
    }

    @Test
    void updateProfile_SelfAccess_UpdatesNicknamePhoneLocaleTimezone() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setNickname(JsonNullable.of("三哥"));
        request.setPhone(JsonNullable.of("13900139000"));
        request.setLocale(JsonNullable.of("en-US"));
        request.setTimezone(JsonNullable.of("America/New_York"));

        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = userService.updateProfile(selfUserId, "testuser", request);

        assertEquals("三哥", response.getNickname());
        assertEquals("13900139000", response.getPhone());
        assertEquals("en-US", response.getLocale());
        assertEquals("America/New_York", response.getTimezone());
    }

    @Test
    void updateProfile_LocaleAbsent_DoesNotModifyLocale() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setNickname(JsonNullable.of("三哥"));
        request.setLocale(JsonNullable.undefined());

        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = userService.updateProfile(selfUserId, "testuser", request);

        assertEquals("zh-CN", response.getLocale());
    }

    @Test
    void updateProfile_LocaleExplicitNull_ThrowsBadRequest() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setLocale(JsonNullable.of(null));

        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateProfile(selfUserId, "testuser", request));

        assertEquals("USER_004", ex.getErrorCode());
    }

    @Test
    void updateProfile_LocaleEmpty_ThrowsBadRequest() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setLocale(JsonNullable.of(""));

        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateProfile(selfUserId, "testuser", request));

        assertEquals("USER_004", ex.getErrorCode());
    }

    @Test
    void updateProfile_LocaleUnsupported_ThrowsBadRequest() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setLocale(JsonNullable.of("fr-FR"));

        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateProfile(selfUserId, "testuser", request));

        assertEquals("USER_004", ex.getErrorCode());
    }

    @Test
    void updateProfile_OtherUser_ThrowsNotFound() {
        UUID otherUserId = UUID.randomUUID();
        when(userAccessService.requireVisibleUser(eq(otherUserId), eq("testuser")))
                .thenThrow(new BusinessException("USER_003", "User not found"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateProfile(otherUserId, "testuser", new UserUpdateRequest()));

        assertEquals("USER_003", ex.getErrorCode());
    }

    @Test
    void changePassword_Success_UpdatesPassword() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPasswordEnvelope(passwordEnvelope("nonce-old"));
        request.setNewPasswordEnvelope(passwordEnvelope("nonce-new"));

        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);
        when(passwordEnvelopeDecryptor.decrypt(eq(request.getOldPasswordEnvelope()), eq("oldPasswordEnvelope"), anySet()))
                .thenReturn("OldPass123!");
        when(passwordEnvelopeDecryptor.decrypt(eq(request.getNewPasswordEnvelope()), eq("newPasswordEnvelope"), anySet()))
                .thenReturn("NewPass456!");
        when(passwordEncoder.matches("OldPass123!", "encoded-old-password")).thenReturn(true);
        when(passwordEncoder.encode("NewPass456!")).thenReturn("encoded-new-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.changePassword(selfUserId, "testuser", request);

        assertEquals("encoded-new-password", currentUser.getPassword());
        verify(passwordStrengthValidator).validatePresentPassword("OldPass123!", "oldPasswordEnvelope");
        verify(passwordStrengthValidator).validateStrongPassword("NewPass456!", "newPasswordEnvelope");
    }

    @Test
    void changePassword_WrongOldPassword_ThrowsAuth001() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPasswordEnvelope(passwordEnvelope("nonce-old"));
        request.setNewPasswordEnvelope(passwordEnvelope("nonce-new"));

        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);
        when(passwordEnvelopeDecryptor.decrypt(eq(request.getOldPasswordEnvelope()), eq("oldPasswordEnvelope"), anySet()))
                .thenReturn("OldPass123!");
        when(passwordEnvelopeDecryptor.decrypt(eq(request.getNewPasswordEnvelope()), eq("newPasswordEnvelope"), anySet()))
                .thenReturn("NewPass456!");
        when(passwordEncoder.matches("OldPass123!", "encoded-old-password")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(selfUserId, "testuser", request));

        assertEquals("AUTH_001", ex.getErrorCode());
    }

    @Test
    void changePassword_PasswordEnvelopeProtocolError_PropagatesBusinessException() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPasswordEnvelope(passwordEnvelope("nonce-old"));
        request.setNewPasswordEnvelope(passwordEnvelope("nonce-new"));

        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);
        when(passwordEnvelopeDecryptor.decrypt(eq(request.getOldPasswordEnvelope()), eq("oldPasswordEnvelope"), anySet()))
                .thenReturn("OldPass123!");
        when(passwordEnvelopeDecryptor.decrypt(eq(request.getNewPasswordEnvelope()), eq("newPasswordEnvelope"), anySet()))
                .thenThrow(new BusinessException("AUTH_007", "Password encryption payload is invalid"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(selfUserId, "testuser", request));

        assertEquals("AUTH_007", ex.getErrorCode());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void uploadAvatar_Success_OverridesUrlAndDeletesOldFile() {
        currentUser.setAvatarUrl("uploads/avatars/old-avatar.png");
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "avatar".getBytes());

        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);
        when(avatarStorageService.store(file)).thenReturn("uploads/avatars/new-avatar.png");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AvatarUploadResponse response = userService.uploadAvatar(selfUserId, "testuser", file);

        assertEquals("uploads/avatars/new-avatar.png", response.getAvatarUrl());
        verify(avatarStorageService).delete("uploads/avatars/old-avatar.png");
    }

    @Test
    void uploadAvatar_DeleteOldFileFailure_DoesNotRollback() {
        currentUser.setAvatarUrl("uploads/avatars/old-avatar.png");
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "avatar".getBytes());

        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);
        when(avatarStorageService.store(file)).thenReturn("uploads/avatars/new-avatar.png");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new BusinessException("FILE_001", "Delete failed"))
                .when(avatarStorageService).delete("uploads/avatars/old-avatar.png");

        AvatarUploadResponse response = userService.uploadAvatar(selfUserId, "testuser", file);

        assertEquals("uploads/avatars/new-avatar.png", response.getAvatarUrl());
    }

    @Test
    void uploadAvatar_EmptyFile_ThrowsBadRequest() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[0]);
        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.uploadAvatar(selfUserId, "testuser", file));

        assertEquals("USER_004", ex.getErrorCode());
        verify(avatarStorageService, never()).store(any());
    }

    @Test
    void uploadAvatar_UnsupportedType_ThrowsBadRequest() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.gif", "image/gif", "avatar".getBytes());
        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.uploadAvatar(selfUserId, "testuser", file));

        assertEquals("USER_004", ex.getErrorCode());
        verify(avatarStorageService, never()).store(any());
    }

    @Test
    void uploadAvatar_FileTooLarge_ThrowsBadRequest() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[2 * 1024 * 1024 + 1]);
        when(userAccessService.requireVisibleUser(selfUserId, "testuser")).thenReturn(targetUser);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.uploadAvatar(selfUserId, "testuser", file));

        assertEquals("USER_004", ex.getErrorCode());
        verify(avatarStorageService, never()).store(any());
    }

    private PasswordEnvelope passwordEnvelope(String nonce) {
        PasswordEnvelope envelope = new PasswordEnvelope();
        envelope.setKeyId("pwd-key-20260329");
        envelope.setNonce(nonce);
        envelope.setTimestamp(1743211200000L);
        envelope.setCiphertext("ciphertext-" + nonce);
        return envelope;
    }
}
