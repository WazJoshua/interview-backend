package com.josh.interviewj.service;

import com.josh.interviewj.auth.support.PasswordStrengthValidator;
import com.josh.interviewj.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordStrengthValidatorTest {

    private final PasswordStrengthValidator validator = new PasswordStrengthValidator();

    @Test
    void validateStrongPassword_AcceptsCompliantPassword() {
        validator.validateStrongPassword("StrongPass123", "passwordEnvelope");
    }

    @Test
    void validateStrongPassword_RejectsWeakPassword() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> validator.validateStrongPassword("weak", "passwordEnvelope"));

        assertEquals("VALIDATION_ERROR", exception.getErrorCode());
        assertEquals("invalid_password_strength", exception.getDetails().getFirst().getCode());
    }

    @Test
    void validatePresentPassword_RejectsBlankPassword() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> validator.validatePresentPassword("   ", "oldPasswordEnvelope"));

        assertEquals("VALIDATION_ERROR", exception.getErrorCode());
        assertEquals("oldPasswordEnvelope", exception.getDetails().getFirst().getField());
    }
}
