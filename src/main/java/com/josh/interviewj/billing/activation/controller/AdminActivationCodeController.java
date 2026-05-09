package com.josh.interviewj.billing.activation.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.billing.activation.dto.request.CreateActivationCodeBatchRequest;
import com.josh.interviewj.billing.activation.dto.request.CreateActivationCodeRequest;
import com.josh.interviewj.billing.activation.dto.response.ActivationCodeBatchResponse;
import com.josh.interviewj.billing.activation.dto.response.ActivationCodeStatsResponse;
import com.josh.interviewj.billing.activation.dto.response.ActivationCodeView;
import com.josh.interviewj.billing.activation.model.ActivationCode;
import com.josh.interviewj.billing.activation.model.ActivationCodeStatus;
import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import com.josh.interviewj.billing.activation.service.ActivationCodeFormatService;
import com.josh.interviewj.billing.activation.service.ActivationCodeQueryService;
import com.josh.interviewj.billing.activation.service.ActivationCodeService;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/activation-codes")
@RequiredArgsConstructor
public class AdminActivationCodeController {

    private final AdminAccessService adminAccessService;
    private final ActivationCodeService activationCodeService;
    private final ActivationCodeQueryService activationCodeQueryService;
    private final ActivationCodeFormatService activationCodeFormatService;
    private final RuntimeSwitchService runtimeSwitchService;

    @PostMapping
    public ResponseEntity<ApiResponse<ActivationCodeView>> create(
            Authentication authentication,
            @Valid @RequestBody CreateActivationCodeRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        ActivationCode activationCode = activationCodeService.generateCode(actor.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(toView(activationCode)));
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<ActivationCodeBatchResponse>> createBatch(
            Authentication authentication,
            @Valid @RequestBody CreateActivationCodeBatchRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        List<ActivationCode> activationCodes = activationCodeService.generateBatch(actor.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(ActivationCodeBatchResponse.builder()
                .batchId(activationCodes.isEmpty() ? null : activationCodes.getFirst().getBatchId())
                .count(activationCodes.size())
                .codes(activationCodes.stream()
                        .map(activationCode -> activationCodeFormatService.format(activationCode.getCode()))
                        .toList())
                .build()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<ActivationCodeView>>> list(
            Authentication authentication,
            @RequestParam(required = false) ActivationCodeStatus status,
            @RequestParam(required = false) ActivationCodeType codeType,
            @RequestParam(required = false) UUID batchId,
            @RequestParam(required = false) Long createdByUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdTo,
            Pageable pageable
    ) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                activationCodeQueryService.list(
                        status,
                        codeType,
                        batchId,
                        createdByUserId,
                        toLocal(createdFrom),
                        toLocal(createdTo),
                        pageable
                )
        ));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<ActivationCodeStatsResponse>> stats(Authentication authentication) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(activationCodeQueryService.stats()));
    }

    @GetMapping("/export")
    public void export(
            Authentication authentication,
            HttpServletResponse response,
            @RequestParam(required = false) ActivationCodeStatus status,
            @RequestParam(required = false) ActivationCodeType codeType,
            @RequestParam(required = false) UUID batchId,
            @RequestParam(required = false) Long createdByUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdTo
    ) throws IOException {
        adminAccessService.requireAdmin(authentication);
        runtimeSwitchService.requireActivationCodeEnabled();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=activation-codes.csv");
        Writer writer = response.getWriter();
        writer.write('\uFEFF');
        activationCodeQueryService.writeCsv(
                status,
                codeType,
                batchId,
                createdByUserId,
                toLocal(createdFrom),
                toLocal(createdTo),
                writer
        );
    }

    @PostMapping("/{id}/void")
    public ResponseEntity<ApiResponse<Void>> voidCode(Authentication authentication, @PathVariable Long id) {
        adminAccessService.requireAdmin(authentication);
        activationCodeService.voidCode(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private ActivationCodeView toView(ActivationCode activationCode) {
        return ActivationCodeView.builder()
                .id(activationCode.getId())
                .code(activationCodeFormatService.format(activationCode.getCode()))
                .codeType(activationCode.getCodeType())
                .status(activationCode.getStatus())
                .billingPlanVersionId(activationCode.getBillingPlanVersionId())
                .subscriptionDurationDays(activationCode.getSubscriptionDurationDays())
                .creditAmountMicros(activationCode.getCreditAmountMicros())
                .expiresAt(toOffset(activationCode.getExpiresAt()))
                .redeemedByUserId(activationCode.getRedeemedByUserId())
                .redeemedAt(toOffset(activationCode.getRedeemedAt()))
                .batchId(activationCode.getBatchId())
                .createdByUserId(activationCode.getCreatedByUserId())
                .note(activationCode.getNote())
                .createdAt(toOffset(activationCode.getCreatedAt()))
                .build();
    }

    private static OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
