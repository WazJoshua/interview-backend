package com.josh.interviewj.auth.controller;

import com.josh.interviewj.auth.dto.request.InviteCodeQueryRequest;
import com.josh.interviewj.auth.dto.response.InviteCodeView;
import com.josh.interviewj.auth.service.InviteCodeService;
import com.josh.interviewj.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/invite-codes")
@Tag(name = "InviteCode", description = "Invite code APIs")
public class InviteCodeController {

    private final InviteCodeService inviteCodeService;

    @PostMapping
    @Operation(summary = "Create invite code")
    public ResponseEntity<ApiResponse<InviteCodeView>> createInviteCode(Authentication authentication) {
        InviteCodeView response = inviteCodeService.createInviteCode(authentication);
        return ResponseEntity.created(URI.create("/api/v1/invite-codes/" + response.getId()))
                .body(ApiResponse.created(response));
    }

    @GetMapping
    @Operation(summary = "List invite codes")
    public ResponseEntity<ApiResponse<Page<InviteCodeView>>> listInviteCodes(
            Authentication authentication,
            @Valid @ModelAttribute InviteCodeQueryRequest request
    ) {
        Page<InviteCodeView> response = inviteCodeService.listInviteCodes(authentication, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
