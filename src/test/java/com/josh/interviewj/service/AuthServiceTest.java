package com.josh.interviewj.service;

import com.josh.interviewj.auth.model.InviteCode;
import com.josh.interviewj.auth.dto.request.PasswordEnvelope;
import com.josh.interviewj.auth.service.AuthService;
import com.josh.interviewj.auth.service.InviteCodeService;
import com.josh.interviewj.auth.service.TokenBlacklistService;
import com.josh.interviewj.auth.dto.request.LoginRequest;
import com.josh.interviewj.auth.dto.request.ForgotPasswordRequest;
import com.josh.interviewj.auth.dto.request.RegisterRequest;
import com.josh.interviewj.auth.dto.response.AuthResponse;
import com.josh.interviewj.auth.dto.response.UserResponse;
import com.josh.interviewj.auth.dto.request.ResetPasswordRequest;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.model.PasswordResetToken;
import com.josh.interviewj.auth.repository.PasswordResetTokenRepository;
import com.josh.interviewj.auth.support.PasswordEnvelopeDecryptor;
import com.josh.interviewj.auth.support.PasswordStrengthValidator;
import com.josh.interviewj.auth.service.PasswordResetNotificationService;
import com.josh.interviewj.billing.service.LegacyVipCompatibilityService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.security.JwtUtil;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private InviteCodeService inviteCodeService;

    @Mock
    private Clock clock;

    @Mock
    private PasswordEnvelopeDecryptor passwordEnvelopeDecryptor;

    @Mock
    private PasswordStrengthValidator passwordStrengthValidator;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private PasswordResetNotificationService passwordResetNotificationService;

    @Mock
    private LegacyVipCompatibilityService legacyVipCompatibilityService;

    @InjectMocks
    private AuthService authService;

    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;
    private ForgotPasswordRequest forgotPasswordRequest;
    private ResetPasswordRequest resetPasswordRequest;
    private User testUser;

    @BeforeEach
    void setUp() {
        registerRequest = new RegisterRequest();
        registerRequest.setUsername("testuser");
        registerRequest.setEmail("test@example.com");
        registerRequest.setPasswordEnvelope(passwordEnvelope("nonce-register"));
        registerRequest.setNickname("测试用户");
        registerRequest.setInviteCode(null);
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-03-26T00:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));

        loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPasswordEnvelope(passwordEnvelope("nonce-login"));

        forgotPasswordRequest = new ForgotPasswordRequest();
        forgotPasswordRequest.setEmail("test@example.com");

        resetPasswordRequest = new ResetPasswordRequest();
        resetPasswordRequest.setResetToken("reset-token-001");
        resetPasswordRequest.setNewPasswordEnvelope(passwordEnvelope("nonce-reset"));

        testUser = User.builder()
                .id(1L)
                .externalId(UUID.randomUUID())
                .username("testuser")
                .email("test@example.com")
                .password("$2a$10$encryptedPassword")
                .nickname("测试用户")
                .phone("13800138000")
                .locale("zh-CN")
                .timezone("Asia/Shanghai")
                .lastLoginAt(LocalDateTime.of(2026, 3, 13, 9, 0))
                .status("ACTIVE")
                .loginAttempts(0)
                .build();
        testUser.addRole("USER");
        lenient().when(legacyVipCompatibilityService.appendLegacyVip(eq(1L), anySet())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void register_Success() {
        // Given
        when(inviteCodeService.normalizeForRegistration(null)).thenReturn("");
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(inviteCodeService.lockAndValidateForRegistration("")).thenReturn(Optional.empty());
        when(passwordEnvelopeDecryptor.decrypt(eq(registerRequest.getPasswordEnvelope()), eq("passwordEnvelope"), anySet()))
                .thenReturn("TestPass123!");
        when(passwordEncoder.encode("TestPass123!")).thenReturn("$2a$10$encodedPassword");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(testUser);
        when(userRepository.save(any(User.class))).thenReturn(testUser);

        // When
        UserResponse result = authService.register(registerRequest);

        // Then
        assertNotNull(result);
        assertEquals("testuser", result.getUsername());
        assertEquals("test@example.com", result.getEmail());
        assertEquals("测试用户", result.getNickname());
        
        verify(inviteCodeService).normalizeForRegistration(null);
        verify(inviteCodeService).requireInviteCodeIfConfigured("");
        verify(inviteCodeService).lockAndValidateForRegistration("");
        verify(userRepository).existsByUsername("testuser");
        verify(userRepository).existsByEmail("test@example.com");
        verify(passwordEnvelopeDecryptor).decrypt(eq(registerRequest.getPasswordEnvelope()), eq("passwordEnvelope"), anySet());
        verify(passwordStrengthValidator).validateStrongPassword("TestPass123!", "passwordEnvelope");
        verify(passwordEncoder).encode("TestPass123!");
        verify(userRepository).saveAndFlush(any(User.class));
        verify(userRepository).save(any(User.class));
    }

    @Test
    void getCurrentUserByUsername_WhenSubscriptionDerivedVipEnabled_ReturnsVipInRoles() {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(legacyVipCompatibilityService.appendLegacyVip(eq(1L), anySet())).thenReturn(Set.of("USER", "VIP"));

        UserResponse response = authService.getCurrentUserByUsername("testuser");

        assertThat(response.getRoles()).contains("USER", "VIP");
    }

    @Test
    void register_WithValidInviteCode_ConsumesInviteCode() {
        InviteCode inviteCode = InviteCode.builder()
                .codeNormalized("ABCDWXYZ2345")
                .createdByUserId(99L)
                .expiresAt(LocalDateTime.of(2026, 3, 27, 0, 0))
                .build();
        User savedUser = User.builder()
                .id(7L)
                .externalId(UUID.randomUUID())
                .username("testuser")
                .email("test@example.com")
                .password("$2a$10$encodedPassword")
                .nickname("测试用户")
                .status("ACTIVE")
                .locale("zh-CN")
                .timezone("Asia/Shanghai")
                .build();

        registerRequest.setInviteCode("ABCD-WXYZ-2345");

        when(inviteCodeService.normalizeForRegistration("ABCD-WXYZ-2345")).thenReturn("ABCDWXYZ2345");
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(inviteCodeService.lockAndValidateForRegistration("ABCDWXYZ2345")).thenReturn(Optional.of(inviteCode));
        when(passwordEnvelopeDecryptor.decrypt(eq(registerRequest.getPasswordEnvelope()), eq("passwordEnvelope"), anySet()))
                .thenReturn("TestPass123!");
        when(passwordEncoder.encode("TestPass123!")).thenReturn("$2a$10$encodedPassword");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse result = authService.register(registerRequest);

        assertNotNull(result);
        assertEquals(7L, inviteCode.getUsedByUserId());
        assertNotNull(inviteCode.getUsedAt());
    }

    @Test
    void register_InvalidInvite_PropagatesBusinessException() {
        registerRequest.setInviteCode("bad");
        when(inviteCodeService.normalizeForRegistration("bad")).thenReturn("BAD");
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(inviteCodeService.lockAndValidateForRegistration("BAD"))
                .thenThrow(new BusinessException("INVITE_002", "Invite code is invalid"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.register(registerRequest));

        assertEquals("INVITE_002", exception.getErrorCode());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void register_UsernameExists_DoesNotLockInviteCode() {
        registerRequest.setInviteCode("bad");
        when(inviteCodeService.normalizeForRegistration("bad")).thenReturn("BAD");
        when(userRepository.existsByUsername("testuser")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.register(registerRequest));

        assertEquals("USER_001", exception.getErrorCode());
        verify(inviteCodeService, never()).lockAndValidateForRegistration(any());
    }

    @Test
    void register_UsernameConstraintViolation_MapsToUser001() {
        when(inviteCodeService.normalizeForRegistration(null)).thenReturn("");
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(inviteCodeService.lockAndValidateForRegistration("")).thenReturn(Optional.empty());
        when(passwordEnvelopeDecryptor.decrypt(eq(registerRequest.getPasswordEnvelope()), eq("passwordEnvelope"), anySet()))
                .thenReturn("TestPass123!");
        when(passwordEncoder.encode("TestPass123!")).thenReturn("$2a$10$encodedPassword");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException(
                "duplicate username",
                new ConstraintViolationException("duplicate username", new SQLException("duplicate"), "uq_users_username")
        ));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.register(registerRequest));

        assertEquals("USER_001", exception.getErrorCode());
        assertEquals("Username already exists", exception.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_EmailConstraintViolation_MapsToUser002() {
        when(inviteCodeService.normalizeForRegistration(null)).thenReturn("");
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(inviteCodeService.lockAndValidateForRegistration("")).thenReturn(Optional.empty());
        when(passwordEnvelopeDecryptor.decrypt(eq(registerRequest.getPasswordEnvelope()), eq("passwordEnvelope"), anySet()))
                .thenReturn("TestPass123!");
        when(passwordEncoder.encode("TestPass123!")).thenReturn("$2a$10$encodedPassword");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException(
                "duplicate email",
                new ConstraintViolationException("duplicate email", new SQLException("duplicate"), "uq_users_email")
        ));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.register(registerRequest));

        assertEquals("USER_002", exception.getErrorCode());
        assertEquals("Email already registered", exception.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_UsernameExists_ThrowsException() {
        // Given
        when(inviteCodeService.normalizeForRegistration(null)).thenReturn("");
        when(userRepository.existsByUsername("testuser")).thenReturn(true);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, 
            () -> authService.register(registerRequest));
        
        assertEquals("USER_001", exception.getErrorCode());
        assertEquals("Username already exists", exception.getMessage());
        
        verify(inviteCodeService).normalizeForRegistration(null);
        verify(inviteCodeService).requireInviteCodeIfConfigured("");
        verify(inviteCodeService, never()).lockAndValidateForRegistration(any());
        verify(userRepository).existsByUsername("testuser");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_EmailExists_ThrowsException() {
        // Given
        when(inviteCodeService.normalizeForRegistration(null)).thenReturn("");
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, 
            () -> authService.register(registerRequest));
        
        assertEquals("USER_002", exception.getErrorCode());
        assertEquals("Email already registered", exception.getMessage());
        
        verify(inviteCodeService).normalizeForRegistration(null);
        verify(inviteCodeService).requireInviteCodeIfConfigured("");
        verify(inviteCodeService, never()).lockAndValidateForRegistration(any());
        verify(userRepository).existsByUsername("testuser");
        verify(userRepository).existsByEmail("test@example.com");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void login_Success() {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(passwordEnvelopeDecryptor.decrypt(eq(loginRequest.getPasswordEnvelope()), eq("passwordEnvelope"), anySet()))
                .thenReturn("TestPass123!");
        when(passwordEncoder.matches("TestPass123!", "$2a$10$encryptedPassword")).thenReturn(true);
        when(jwtUtil.generateAccessToken("testuser")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken("testuser")).thenReturn("refresh-token");
        when(jwtUtil.getAccessTokenExpiration()).thenReturn(7200L);
        when(jwtUtil.getRefreshTokenExpiration()).thenReturn(604800L);
        doNothing().when(tokenBlacklistService).registerUserToken(anyString(), anyString());

        // When
        AuthResponse result = authService.login(loginRequest);

        // Then
        assertNotNull(result);
        assertEquals("access-token", result.getAccessToken());
        assertEquals("refresh-token", result.getRefreshToken());
        assertEquals("Bearer", result.getTokenType());
        assertEquals(7200L, result.getExpiresIn());
        assertEquals(604800L, result.getRefreshTokenExpiresIn());
        assertNotNull(result.getUser());
        assertEquals("13800138000", result.getUser().getPhone());
        assertEquals("zh-CN", result.getUser().getLocale());
        assertEquals("Asia/Shanghai", result.getUser().getTimezone());
        assertNotNull(result.getUser().getLastLoginAt());
        assertEquals(Set.of("USER"), result.getUser().getRoles());
        
        verify(userRepository).findByUsername("testuser");
        verify(passwordEnvelopeDecryptor).decrypt(eq(loginRequest.getPasswordEnvelope()), eq("passwordEnvelope"), anySet());
        verify(passwordStrengthValidator).validatePresentPassword("TestPass123!", "passwordEnvelope");
        verify(passwordEncoder).matches("TestPass123!", "$2a$10$encryptedPassword");
        verify(userRepository).updateLastLoginAt(eq(testUser.getId()), any(LocalDateTime.class));
        verify(jwtUtil).generateAccessToken("testuser");
        verify(jwtUtil).generateRefreshToken("testuser");
    }

    @Test
    void getCurrentUserByUsername_ReturnsExpandedUserResponse() {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

        UserResponse result = authService.getCurrentUserByUsername("testuser");

        assertNotNull(result);
        assertEquals(testUser.getExternalId(), result.getId());
        assertEquals("13800138000", result.getPhone());
        assertEquals("zh-CN", result.getLocale());
        assertEquals("Asia/Shanghai", result.getTimezone());
        assertEquals(LocalDateTime.of(2026, 3, 13, 9, 0), result.getLastLoginAt());
        assertEquals(Set.of("USER"), result.getRoles());
    }

    @Test
    void login_WrongPassword_ThrowsException() {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(passwordEnvelopeDecryptor.decrypt(eq(loginRequest.getPasswordEnvelope()), eq("passwordEnvelope"), anySet()))
                .thenReturn("TestPass123!");
        when(passwordEncoder.matches("TestPass123!", "$2a$10$encryptedPassword")).thenReturn(false);

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, 
            () -> authService.login(loginRequest));
        
        assertEquals("AUTH_001", exception.getErrorCode());
        assertEquals("Invalid username or password", exception.getMessage());
        
        verify(userRepository).findByUsername("testuser");
        verify(passwordEncoder).matches("TestPass123!", "$2a$10$encryptedPassword");
        verify(userRepository).incrementFailedLoginAttempts(testUser.getId());
    }

    @Test
    void login_UserNotFound_ThrowsException() {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("testuser")).thenReturn(Optional.empty());
        when(passwordEnvelopeDecryptor.decrypt(eq(loginRequest.getPasswordEnvelope()), eq("passwordEnvelope"), anySet()))
                .thenReturn("TestPass123!");

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, 
            () -> authService.login(loginRequest));
        
        assertEquals("AUTH_001", exception.getErrorCode());
        assertEquals("Invalid username or password", exception.getMessage());
        
        verify(userRepository).findByUsername("testuser");
        verify(userRepository).findByEmail("testuser");
    }

    @Test
    void forgotPassword_KnownEmail_InvalidatesOldTokensAndSendsNotification() {
        when(userRepository.findByEmailForUpdate("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        authService.forgotPassword(forgotPasswordRequest);

        verify(passwordResetTokenRepository).invalidateActiveTokensByUserId(eq(testUser.getId()), any(LocalDateTime.class));
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
        verify(passwordResetNotificationService).sendPasswordReset(eq("test@example.com"), any(String.class), any(LocalDateTime.class));
    }

    @Test
    void forgotPassword_UnknownEmail_ReturnsWithoutPersistingToken() {
        when(userRepository.findByEmailForUpdate("test@example.com")).thenReturn(Optional.empty());

        authService.forgotPassword(forgotPasswordRequest);

        verify(passwordResetTokenRepository, never()).save(any());
        verify(passwordResetNotificationService, never()).sendPasswordReset(any(), any(), any());
    }

    @Test
    void resetPassword_Success_UpdatesPasswordAndMarksTokenUsed() {
        PasswordResetToken token = PasswordResetToken.builder()
                .id(10L)
                .userId(testUser.getId())
                .tokenHash("hashed-token")
                .expiresAt(LocalDateTime.now(clock).plusMinutes(30))
                .build();

        when(passwordResetTokenRepository.findActiveByTokenHash(any(String.class), any(LocalDateTime.class)))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(passwordEnvelopeDecryptor.decrypt(eq(resetPasswordRequest.getNewPasswordEnvelope()), eq("newPasswordEnvelope"), anySet()))
                .thenReturn("ResetPass456!");
        when(passwordEncoder.encode("ResetPass456!")).thenReturn("encoded-reset-password");

        authService.resetPassword(resetPasswordRequest);

        assertEquals("encoded-reset-password", testUser.getPassword());
        assertNotNull(token.getUsedAt());
        verify(passwordStrengthValidator).validateStrongPassword("ResetPass456!", "newPasswordEnvelope");
    }

    @Test
    void resetPassword_InvalidToken_ThrowsAuth008() {
        when(passwordResetTokenRepository.findActiveByTokenHash(any(String.class), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.resetPassword(resetPasswordRequest));

        assertEquals("AUTH_008", exception.getErrorCode());
    }

    @Test
    void register_PasswordEnvelopeProtocolError_PropagatesBusinessException() {
        when(passwordEnvelopeDecryptor.decrypt(eq(registerRequest.getPasswordEnvelope()), eq("passwordEnvelope"), anySet()))
                .thenThrow(new BusinessException("AUTH_007", "Password encryption payload is invalid"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.register(registerRequest));

        assertEquals("AUTH_007", exception.getErrorCode());
        verify(passwordEncoder, never()).encode(any());
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
