package com.josh.interviewj.usage.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.common.api.RequestIdContext;
import com.josh.interviewj.llm.prompt.model.LlmPromptTemplate;
import com.josh.interviewj.llm.prompt.model.LlmPromptTemplateRevision;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplateCreateRequest;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplateListQuery;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplatePreviewRequest;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplateRevisionCreateRequest;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplateToggleRequest;
import com.josh.interviewj.usage.dto.response.AdminPromptTemplateDetailResponse;
import com.josh.interviewj.usage.dto.response.AdminPromptTemplateListItemResponse;
import com.josh.interviewj.usage.dto.response.AdminPromptTemplatePreviewResponse;
import com.josh.interviewj.usage.dto.response.AdminPromptTemplateRevisionResponse;
import com.josh.interviewj.usage.service.AdminPromptTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Admin API for managing LLM prompt templates.
 */
@RestController
@RequestMapping("/api/v1/admin/llm/prompt-templates")
@RequiredArgsConstructor
public class AdminPromptTemplateController {

    private final AdminPromptTemplateService service;
    private final AdminAccessService adminAccessService;

    /**
     * Verify admin access and extract actorUserId from current authentication.
     */
    private Long requireAdminUserId(Authentication authentication) {
        return requireAdmin(authentication).getId();
    }

    /**
     * Verify admin access only (for read operations).
     */
    private User requireAdmin(Authentication authentication) {
        return adminAccessService.requireAdmin(authentication);
    }

    private ResponseEntity<ApiResponse<AdminPromptTemplatePreviewResponse>> badPreviewResponse(
            AdminPromptTemplatePreviewResponse response
    ) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<AdminPromptTemplatePreviewResponse>builder()
                        .code(400)
                        .message(response.errorMessage())
                        .data(response)
                        .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                        .requestId(RequestIdContext.getOrCreate())
                        .build());
    }

    /**
     * List all prompt templates with optional filtering.
     */
    @GetMapping
    public ApiResponse<Page<AdminPromptTemplateListItemResponse>> listTemplates(
            Authentication authentication,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) Boolean enabled,
            Pageable pageable
    ) {
        requireAdmin(authentication);
        AdminPromptTemplateListQuery query = new AdminPromptTemplateListQuery(domain, purpose, enabled);
        return ApiResponse.success(service.listTemplates(query, pageable));
    }

    /**
     * Get template detail.
     */
    @GetMapping("/{templateKey}")
    public ApiResponse<AdminPromptTemplateDetailResponse> getTemplate(
            Authentication authentication,
            @PathVariable String templateKey) {
        requireAdmin(authentication);
        return ApiResponse.success(service.getTemplate(templateKey));
    }

    /**
     * Create new template with initial revision.
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> createTemplate(
            Authentication authentication,
            @RequestBody AdminPromptTemplateCreateRequest request) {
        Long actorUserId = requireAdminUserId(authentication);
        LlmPromptTemplate template = service.createTemplate(actorUserId, request);
        return ApiResponse.created(Map.of(
                "templateKey", template.getTemplateKey(),
                "id", template.getId()
        ));
    }

    /**
     * Create new revision for existing template.
     */
    @PostMapping("/{templateKey}/revisions")
    public ApiResponse<Map<String, Object>> createRevision(
            Authentication authentication,
            @PathVariable String templateKey,
            @RequestBody AdminPromptTemplateRevisionCreateRequest request
    ) {
        Long actorUserId = requireAdminUserId(authentication);
        LlmPromptTemplateRevision revision = service.createRevision(actorUserId, templateKey, request);
        return ApiResponse.created(Map.of(
                "revisionNo", revision.getRevisionNo(),
                "id", revision.getId()
        ));
    }

    /**
     * Get all revisions for a template.
     */
    @GetMapping("/{templateKey}/revisions")
    public ApiResponse<List<AdminPromptTemplateRevisionResponse>> getRevisions(
            Authentication authentication,
            @PathVariable String templateKey) {
        requireAdmin(authentication);
        return ApiResponse.success(service.getRevisions(templateKey));
    }

    /**
     * Publish a revision as active.
     */
    @PostMapping("/{templateKey}/revisions/{revisionNo}/publish")
    public ApiResponse<Void> publishRevision(
            Authentication authentication,
            @PathVariable String templateKey,
            @PathVariable Integer revisionNo
    ) {
        Long actorUserId = requireAdminUserId(authentication);
        service.publishRevision(actorUserId, templateKey, revisionNo);
        return ApiResponse.success(null);
    }

    /**
     * Toggle template enabled status.
     */
    @PatchMapping("/{templateKey}/toggle")
    public ApiResponse<Void> toggleTemplate(
            Authentication authentication,
            @PathVariable String templateKey,
            @RequestBody AdminPromptTemplateToggleRequest request
    ) {
        Long actorUserId = requireAdminUserId(authentication);
        service.toggleTemplate(actorUserId, templateKey, request);
        return ApiResponse.success(null);
    }

    /**
     * Preview draft template content.
     */
    @PostMapping("/{templateKey}/preview")
    public ResponseEntity<ApiResponse<AdminPromptTemplatePreviewResponse>> previewDraft(
            Authentication authentication,
            @PathVariable String templateKey,
            @RequestBody AdminPromptTemplatePreviewRequest request
    ) {
        requireAdmin(authentication);
        AdminPromptTemplatePreviewResponse response = service.previewDraft(request);
        if (!response.success()) {
            return badPreviewResponse(response);
        }
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
