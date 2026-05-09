package com.josh.interviewj.user.controller;

import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.usage.dto.request.UserUsageHistoryQuery;
import com.josh.interviewj.usage.dto.response.UserUsageHistoryItemResponse;
import com.josh.interviewj.usage.dto.response.UserUsageOverviewResponse;
import com.josh.interviewj.usage.service.UserUsageHistoryQueryService;
import com.josh.interviewj.usage.service.UserUsageOverviewQueryService;
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
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class CurrentUserUsageController {

    private final UserUsageOverviewQueryService userUsageOverviewQueryService;
    private final UserUsageHistoryQueryService userUsageHistoryQueryService;

    @GetMapping("/usage-overview")
    public ResponseEntity<ApiResponse<UserUsageOverviewResponse>> getUsageOverview(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                userUsageOverviewQueryService.getCurrentUserOverview(authentication.getName())
        ));
    }

    @GetMapping("/usage-history")
    public ResponseEntity<ApiResponse<Page<UserUsageHistoryItemResponse>>> getUsageHistory(
            Authentication authentication,
            @Valid @ModelAttribute UserUsageHistoryQuery query
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                userUsageHistoryQueryService.getCurrentUserHistory(authentication.getName(), query)
        ));
    }
}
