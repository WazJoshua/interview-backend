package com.josh.interviewj.admin.controller;

import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.common.api.RequestIdContext;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesAdminView;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesSnapshot;
import com.josh.interviewj.common.settings.dto.UpdateRuntimeSwitchesRequest;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/runtime-switches")
@RequiredArgsConstructor
public class AdminRuntimeSwitchController {

    private static final String RESOURCE_ID_RUNTIME_SWITCHES = "runtime-switches";

    private final AdminAccessService adminAccessService;
    private final RuntimeSwitchService runtimeSwitchService;
    private final AdminOperationLogService adminOperationLogService;

    @GetMapping
    public ResponseEntity<ApiResponse<RuntimeSwitchesAdminView>> getRuntimeSwitches(Authentication authentication) {
        adminAccessService.requireAdmin(authentication);
        RuntimeSwitchesAdminView view = runtimeSwitchService.getAdminView();
        return ResponseEntity.ok(ApiResponse.success(view));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<RuntimeSwitchesAdminView>> updateRuntimeSwitches(
            Authentication authentication,
            @Valid @RequestBody UpdateRuntimeSwitchesRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        RuntimeSwitchesAdminView beforeSnapshot = runtimeSwitchService.getAdminView();
        RuntimeSwitchesSnapshot updatedSnapshot = runtimeSwitchService.updateSwitches(request, actor.getId());
        RuntimeSwitchesAdminView afterSnapshot = RuntimeSwitchesAdminView.fromSnapshot(updatedSnapshot);

        String requestId = RequestIdContext.getOrCreate();
        adminOperationLogService.recordUpdate(
                actor.getId(),
                AdminOperationResourceType.SYSTEM_SETTING,
                RESOURCE_ID_RUNTIME_SWITCHES,
                requestId,
                beforeSnapshot,
                afterSnapshot,
                Map.of("expectedRevision", request.getExpectedRevision())
        );

        return ResponseEntity.ok(ApiResponse.success(afterSnapshot));
    }
}
