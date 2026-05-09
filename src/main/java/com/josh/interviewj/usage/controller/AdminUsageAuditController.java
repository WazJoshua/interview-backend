package com.josh.interviewj.usage.controller;

import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.usage.dto.request.AdminUsageEventsQuery;
import com.josh.interviewj.usage.dto.request.AdminUsageSummaryQuery;
import com.josh.interviewj.usage.dto.response.AdminUsageEventResponse;
import com.josh.interviewj.usage.dto.response.AdminUsageSummaryResponse;
import com.josh.interviewj.usage.service.AdminUsageAuditQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminUsageAuditController {

    private final AdminAccessService adminAccessService;
    private final AdminUsageAuditQueryService adminUsageAuditQueryService;

    @GetMapping("/usage-events")
    public ResponseEntity<ApiResponse<Page<AdminUsageEventResponse>>> getUsageEvents(
            Authentication authentication,
            @Valid @ModelAttribute AdminUsageEventsQuery query
    ) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminUsageAuditQueryService.getUsageEvents(query)));
    }

    @GetMapping("/usage-summary")
    public ResponseEntity<ApiResponse<AdminUsageSummaryResponse>> getUsageSummary(
            Authentication authentication,
            @Valid @ModelAttribute AdminUsageSummaryQuery query
    ) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminUsageAuditQueryService.getUsageSummary(query)));
    }
}
