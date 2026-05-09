package com.josh.interviewj.common.settings.controller;

import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesPublicView;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/runtime-switches")
@RequiredArgsConstructor
public class RuntimeSwitchController {

    private final RuntimeSwitchService runtimeSwitchService;

    @GetMapping
    public ResponseEntity<ApiResponse<RuntimeSwitchesPublicView>> getRuntimeSwitches() {
        return ResponseEntity.ok(ApiResponse.success(runtimeSwitchService.getPublicView()));
    }
}
