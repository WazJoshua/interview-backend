package com.josh.interviewj.auth.service;

import com.josh.interviewj.auth.dto.request.ForgotPasswordRequest;
import com.josh.interviewj.auth.dto.request.LoginRequest;
import com.josh.interviewj.auth.dto.request.RegisterRequest;
import com.josh.interviewj.auth.dto.request.RefreshTokenRequest;
import com.josh.interviewj.auth.dto.request.ResetPasswordRequest;
import com.josh.interviewj.auth.dto.response.AuthResponse;
import com.josh.interviewj.auth.dto.response.RegisterConfigResponse;
import com.josh.interviewj.auth.dto.response.UserResponse;
import com.josh.interviewj.auth.model.InviteCode;
import com.josh.interviewj.auth.model.PasswordResetToken;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.auth.repository.PasswordResetTokenRepository;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.auth.support.PasswordEnvelopeDecryptor;
import com.josh.interviewj.auth.support.PasswordStrengthValidator;
import com.josh.interviewj.common.enums.ContentLocale;
import com.josh.interviewj.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;
    private final InviteCodeService inviteCodeService;
    private final Clock clock;
    private final PasswordEnvelopeDecryptor passwordEnvelopeDecryptor;
    private final PasswordStrengthValidator passwordStrengthValidator;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordResetNotificationService passwordResetNotificationService;
    private final com.josh.interviewj.billing.service.LegacyVipCompatibilityService legacyVipCompatibilityService;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public RegisterConfigResponse getRegisterConfig() {
        return inviteCodeService.getRegisterConfig();
    }

    /**
     * Register a new user.
     *
     * @param request register request
     * @return user response
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String password = passwordEnvelopeDecryptor.decrypt(request.getPasswordEnvelope(), "passwordEnvelope", new HashSet<>());
        passwordStrengthValidator.validateStrongPassword(password, "passwordEnvelope");
        String normalizedInviteCode = inviteCodeService.normalizeForRegistration(request.getInviteCode());
        inviteCodeService.requireInviteCodeIfConfigured(normalizedInviteCode);

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCode.USER_001, "Username already exists");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(ErrorCode.USER_002, "Email already registered");
        }

        Optional<InviteCode> inviteCode = inviteCodeService.lockAndValidateForRegistration(normalizedInviteCode);

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(password))
                .nickname(request.getNickname() != null ? request.getNickname() : request.getUsername())
                .build();

        User savedUser = saveUserWithConstraintMapping(user, request);
        savedUser.addRole("USER");
        savedUser = userRepository.save(savedUser);

        if (inviteCode.isPresent()) {
            InviteCode lockedInviteCode = inviteCode.get();
            lockedInviteCode.setUsedByUserId(savedUser.getId());
            lockedInviteCode.setUsedAt(LocalDateTime.now(clock));
        }

        log.info("用户注册成功: username={}, email={}", savedUser.getUsername(), savedUser.getEmail());

        return convertToUserResponse(savedUser);
    }

    /**
     * Login using username/email and password.
     *
     * @param request login request
     * @return auth response with access/refresh tokens
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String password = passwordEnvelopeDecryptor.decrypt(request.getPasswordEnvelope(), "passwordEnvelope", new HashSet<>());
        passwordStrengthValidator.validatePresentPassword(password, "passwordEnvelope");
        User user = userRepository.findByUsername(request.getUsername())
                .orElseGet(() -> userRepository.findByEmail(request.getUsername())
                        .orElseThrow(() -> new BusinessException("AUTH_001", "Invalid username or password")));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            handleFailedLogin(user);
            throw new BusinessException("AUTH_001", "Invalid username or password");
        }

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException("AUTH_001", "Invalid username or password");
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException("AUTH_001", "Invalid username or password");
        }

        if (user.getLoginAttempts() > 0) {
            userRepository.resetFailedLoginAttempts(user.getId());
        }

        LocalDateTime lastLoginAt = LocalDateTime.now();
        userRepository.updateLastLoginAt(user.getId(), lastLoginAt);
        user.setLastLoginAt(lastLoginAt);

        String accessToken = jwtUtil.generateAccessToken(user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getUsername());

        tokenBlacklistService.registerUserToken(user.getUsername(), accessToken);

        UserResponse userResponse = convertToUserResponse(user);
        
        log.info("用户登录成功: username={}", user.getUsername());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtUtil.getAccessTokenExpiration())
                .refreshTokenExpiresIn(jwtUtil.getRefreshTokenExpiration())
                .user(userResponse)
                .build();
    }

    /**
     * Logout by blacklisting the access token and invalidating all tokens of the user.
     *
     * @param username username
     * @param token access token
     */
    @Transactional
    public void logout(String username, String token) {
        if (token != null && !token.isBlank()) {
            tokenBlacklistService.addToBlacklist(token, jwtUtil.getAccessTokenExpiration());
        }
        tokenBlacklistService.invalidateAllUserTokens(username);
        log.info("用户登出: username={}", username);
    }

    /**
     * Refresh tokens with a refresh token.
     *
     * @param request refresh token request
     * @return new auth response
     */
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();

        if (tokenBlacklistService.isBlacklisted(refreshToken)) {
            throw new BusinessException("AUTH_002", "Refresh token is invalid or expired");
        }
        
        if (!jwtUtil.validateRefreshToken(refreshToken)) {
            throw new BusinessException("AUTH_002", "Refresh token is invalid or expired");
        }

        String username = jwtUtil.extractUsername(refreshToken);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("AUTH_003", "User not found"));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException("AUTH_002", "Refresh token is invalid or expired");
        }

        tokenBlacklistService.addToBlacklist(refreshToken, jwtUtil.getRefreshTokenExpiration());

        String newAccessToken = jwtUtil.generateAccessToken(username);
        String newRefreshToken = jwtUtil.generateRefreshToken(username);

        tokenBlacklistService.registerUserToken(username, newAccessToken);

        UserResponse userResponse = convertToUserResponse(user);

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtUtil.getAccessTokenExpiration())
                .refreshTokenExpiresIn(jwtUtil.getRefreshTokenExpiration())
                .user(userResponse)
                .build();
    }

    /**
     * Get the current user profile by username.
     *
     * @param username username
     * @return user response
     */
    public UserResponse getCurrentUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("USER_003", "User not found"));
        
        return convertToUserResponse(user);
    }

    /**
     * Handle a failed login attempt, incrementing attempts and locking if threshold reached.
     *
     * @param user user entity
     */
    private void handleFailedLogin(User user) {
        int failedAttempts = user.getLoginAttempts() + 1;
        userRepository.incrementFailedLoginAttempts(user.getId());

        if (failedAttempts >= 5) {
            LocalDateTime lockUntil = LocalDateTime.now().plusMinutes(30);
            userRepository.lockUserAccount(user.getId(), lockUntil);
            log.warn("用户账户被锁定: username={}, lockedUntil={}", user.getUsername(), lockUntil);
        }
    }

    /**
     * Convert User entity to a response DTO.
     *
     * @param user user entity
     * @return user response
     */
    private UserResponse convertToUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getExternalId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .phone(user.getPhone())
                .roles(legacyVipCompatibilityService.appendLegacyVip(user.getId(), user.getRoleNames()))
                .locale(ContentLocale.normalizeOrDefault(user.getLocale()))
                .timezone(user.getTimezone())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        Optional<User> optionalUser = userRepository.findByEmailForUpdate(request.getEmail());
        if (optionalUser.isEmpty()) {
            return;
        }

        User user = optionalUser.get();
        LocalDateTime now = LocalDateTime.now(clock);
        passwordResetTokenRepository.invalidateActiveTokensByUserId(user.getId(), now);

        String rawToken = generateRawResetToken();
        LocalDateTime expiresAt = now.plusMinutes(30);
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(user.getId())
                .tokenHash(hashResetToken(rawToken))
                .expiresAt(expiresAt)
                .build();
        passwordResetTokenRepository.save(token);
        passwordResetNotificationService.sendPasswordReset(user.getEmail(), rawToken, expiresAt);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        PasswordResetToken token = passwordResetTokenRepository
                .findActiveByTokenHash(hashResetToken(request.getResetToken()), now)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_008, "Password reset token is invalid or expired"));
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_008, "Password reset token is invalid or expired"));

        String newPassword = passwordEnvelopeDecryptor.decrypt(request.getNewPasswordEnvelope(), "newPasswordEnvelope", new HashSet<>());
        passwordStrengthValidator.validateStrongPassword(newPassword, "newPasswordEnvelope");

        user.setPassword(passwordEncoder.encode(newPassword));
        token.setUsedAt(now);
        userRepository.save(user);
        passwordResetTokenRepository.save(token);
    }

    private User saveUserWithConstraintMapping(User user, RegisterRequest request) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            String constraintName = findConstraintName(ex);
            if ("uq_users_username".equals(constraintName)) {
                throw new BusinessException(ErrorCode.USER_001, "Username already exists", ex);
            }
            if ("uq_users_email".equals(constraintName)) {
                throw new BusinessException(ErrorCode.USER_002, "Email already registered", ex);
            }
            if (userRepository.existsByUsername(request.getUsername())) {
                throw new BusinessException(ErrorCode.USER_001, "Username already exists", ex);
            }
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new BusinessException(ErrorCode.USER_002, "Email already registered", ex);
            }
            throw ex;
        }
    }

    private String findConstraintName(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolationException) {
                return constraintViolationException.getConstraintName();
            }
            current = current.getCause();
        }
        return null;
    }

    private String generateRawResetToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String hashResetToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
