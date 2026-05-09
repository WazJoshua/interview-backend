package com.josh.interviewj.auth.service;

import com.josh.interviewj.auth.config.InviteCodeProperties;
import com.josh.interviewj.auth.dto.request.InviteCodeQueryRequest;
import com.josh.interviewj.auth.dto.response.InviteCodeFormatResponse;
import com.josh.interviewj.auth.dto.response.InviteCodeActorView;
import com.josh.interviewj.auth.dto.response.InviteCodeView;
import com.josh.interviewj.auth.dto.response.RegisterConfigResponse;
import com.josh.interviewj.auth.model.InviteCode;
import com.josh.interviewj.auth.model.InviteCodeStatus;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.InviteCodeRepository;
import com.josh.interviewj.auth.support.InviteCodeCodec;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class InviteCodeService {

    private static final int MAX_CREATE_ATTEMPTS = 10;

    private final InviteCodeProperties inviteCodeProperties;
    private final InviteCodeRepository inviteCodeRepository;
    private final InviteCodeCodec inviteCodeCodec;
    private final InviteCodeAccessService inviteCodeAccessService;
    private final Clock clock;

    public RegisterConfigResponse getRegisterConfig() {
        return RegisterConfigResponse.builder()
                .inviteCodeRequired(inviteCodeProperties.isRequired())
                .inviteCodeFormat(InviteCodeFormatResponse.builder()
                        .length(InviteCodeCodec.CODE_LENGTH)
                        .displayPattern(InviteCodeCodec.DISPLAY_PATTERN)
                        .caseSensitive(InviteCodeCodec.CASE_SENSITIVE)
                        .allowWhitespace(InviteCodeCodec.ALLOW_WHITESPACE)
                        .allowHyphen(InviteCodeCodec.ALLOW_HYPHEN)
                        .build())
                .build();
    }

    public String normalizeForRegistration(String rawInviteCode) {
        return inviteCodeCodec.normalize(rawInviteCode);
    }

    public void requireInviteCodeIfConfigured(String normalizedInviteCode) {
        if (normalizedInviteCode.isEmpty() && inviteCodeProperties.isRequired()) {
            throw new BusinessException(ErrorCode.INVITE_001, "Invite code is required");
        }
    }

    public Optional<InviteCode> lockAndValidateForRegistration(String normalizedInviteCode) {
        if (normalizedInviteCode.isEmpty()) {
            return Optional.empty();
        }

        if (!inviteCodeCodec.isCanonicalFormat(normalizedInviteCode)) {
            throw new BusinessException(ErrorCode.INVITE_002, "Invite code is invalid");
        }

        InviteCode inviteCode = inviteCodeRepository.findByCodeNormalizedForUpdate(normalizedInviteCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITE_002, "Invite code is invalid"));

        if (inviteCode.getUsedAt() != null) {
            throw new BusinessException(ErrorCode.INVITE_003, "Invite code has already been used");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (!inviteCode.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.INVITE_004, "Invite code has expired");
        }

        return Optional.of(inviteCode);
    }

    public InviteCodeView createInviteCode(Authentication authentication) {
        User currentUser = inviteCodeAccessService.requireInviteCodeManager(authentication);
        LocalDateTime now = LocalDateTime.now(clock);

        for (int attempt = 0; attempt < MAX_CREATE_ATTEMPTS; attempt++) {
            String codeNormalized = inviteCodeCodec.generateCanonicalCode();
            InviteCode inviteCode = InviteCode.builder()
                    .codeNormalized(codeNormalized)
                    .createdByUserId(currentUser.getId())
                    .expiresAt(now.plus(inviteCodeProperties.getTtl()))
                    .build();

            try {
                InviteCode savedInviteCode = inviteCodeRepository.saveAndFlush(inviteCode);
                savedInviteCode.setCreatedByUser(currentUser);
                return toView(savedInviteCode);
            } catch (DataIntegrityViolationException ex) {
                if (!"uq_invite_codes_code_normalized".equals(findConstraintName(ex))) {
                    throw ex;
                }
            }
        }

        log.error("邀请码生成重试超过上限");
        throw new IllegalStateException("Invite code generation exceeded retry limit");
    }

    public Page<InviteCodeView> listInviteCodes(Authentication authentication, InviteCodeQueryRequest request) {
        User currentUser = inviteCodeAccessService.requireInviteCodeManager(authentication);
        InviteCodeQueryRequest query = request == null ? new InviteCodeQueryRequest() : request;
        Pageable pageable = PageRequest.of(query.getPage(), query.getSize(), Sort.by("createdAt").descending());
        LocalDateTime now = LocalDateTime.now(clock);

        Page<InviteCode> inviteCodes = findInviteCodes(currentUser, query.getStatus(), pageable, now);
        return inviteCodes.map(this::toView);
    }

    private Page<InviteCode> findInviteCodes(
            User currentUser,
            InviteCodeStatus status,
            Pageable pageable,
            LocalDateTime now
    ) {
        boolean canViewAll = inviteCodeAccessService.canViewAllInviteCodes(currentUser);
        if (status == null) {
            return canViewAll
                    ? inviteCodeRepository.findAll(pageable)
                    : inviteCodeRepository.findByCreatedByUserId(currentUser.getId(), pageable);
        }

        return switch (status) {
            case USED -> canViewAll
                    ? inviteCodeRepository.findByUsedAtIsNotNull(pageable)
                    : inviteCodeRepository.findByCreatedByUserIdAndUsedAtIsNotNull(currentUser.getId(), pageable);
            case UNUSED -> canViewAll
                    ? inviteCodeRepository.findByUsedAtIsNullAndExpiresAtAfter(now, pageable)
                    : inviteCodeRepository.findByCreatedByUserIdAndUsedAtIsNullAndExpiresAtAfter(currentUser.getId(), now, pageable);
            case EXPIRED -> canViewAll
                    ? inviteCodeRepository.findByUsedAtIsNullAndExpiresAtLessThanEqual(now, pageable)
                    : inviteCodeRepository.findByCreatedByUserIdAndUsedAtIsNullAndExpiresAtLessThanEqual(currentUser.getId(), now, pageable);
        };
    }

    private InviteCodeView toView(InviteCode inviteCode) {
        return InviteCodeView.builder()
                .id(inviteCode.getExternalId())
                .code(inviteCodeCodec.formatForDisplay(inviteCode.getCodeNormalized()))
                .status(inviteCodeCodec.calculateStatus(inviteCode.getUsedAt(), inviteCode.getExpiresAt(), LocalDateTime.now(clock)))
                .createdAt(inviteCode.getCreatedAt())
                .expiresAt(inviteCode.getExpiresAt())
                .usedAt(inviteCode.getUsedAt())
                .createdBy(toActorView(inviteCode.getCreatedByUser()))
                .usedBy(toActorView(inviteCode.getUsedByUser()))
                .build();
    }

    private InviteCodeActorView toActorView(User user) {
        if (user == null) {
            return null;
        }
        return InviteCodeActorView.builder()
                .id(user.getExternalId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .build();
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
}
