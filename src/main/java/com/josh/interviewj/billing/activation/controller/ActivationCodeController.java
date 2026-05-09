package com.josh.interviewj.billing.activation.controller;

import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.activation.dto.request.RedeemActivationCodeRequest;
import com.josh.interviewj.billing.activation.dto.response.RedeemResultResponse;
import com.josh.interviewj.billing.activation.service.ActivationCodeService;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activation-codes")
@RequiredArgsConstructor
public class ActivationCodeController {

    private final ActivationCodeService activationCodeService;
    private final UserRepository userRepository;

    @PostMapping("/redeem")
    public ResponseEntity<ApiResponse<RedeemResultResponse>> redeem(
            Authentication authentication,
            @Valid @RequestBody RedeemActivationCodeRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                activationCodeService.redeem(resolveUserId(authentication), request.getCode())
        ));
    }

    private Long resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.AUTH_001, "Unauthorized access");
        }
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003, "Authenticated user not found"))
                .getId();
    }
}
