package com.josh.interviewj.auth.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Public password encryption key metadata exposed to clients.
 */
@Data
@Builder
public class PasswordKeyResponse {

    private String keyId;
    private String algorithm;
    private String publicKeyPem;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
}
