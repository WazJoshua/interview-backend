package com.josh.interviewj.auth.support;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates decrypted password values after envelope decryption.
 */
@Component
public class PasswordStrengthValidator {

    private static final Pattern UPPERCASE_PATTERN = Pattern.compile(".*[A-Z].*");
    private static final Pattern LOWERCASE_PATTERN = Pattern.compile(".*[a-z].*");
    private static final Pattern DIGIT_PATTERN = Pattern.compile(".*\\d.*");

    public void validatePresentPassword(String password, String fieldName) {
        if (password != null && !password.isBlank()) {
            return;
        }
        throw validationException(fieldName, "Password must not be blank");
    }

    public void validateStrongPassword(String password, String fieldName) {
        if (password == null || password.length() < 8 || password.length() > 50
                || !UPPERCASE_PATTERN.matcher(password).matches()
                || !LOWERCASE_PATTERN.matcher(password).matches()
                || !DIGIT_PATTERN.matcher(password).matches()) {
            throw validationException(fieldName,
                    "Password must be 8-50 characters and include uppercase letters, lowercase letters, and numbers");
        }
    }

    private BusinessException validationException(String fieldName, String message) {
        ErrorResponse.ErrorDetails.ErrorDetail detail = ErrorResponse.ErrorDetails.ErrorDetail.builder()
                .field(fieldName)
                .code("invalid_password_strength")
                .message(message)
                .build();
        return new BusinessException("VALIDATION_ERROR", "Request validation failed", List.of(detail));
    }
}
