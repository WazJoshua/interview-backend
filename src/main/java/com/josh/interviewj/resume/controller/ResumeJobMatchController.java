package com.josh.interviewj.resume.controller;

import com.josh.interviewj.resume.dto.request.ResumeJobMatchCreateRequestDTO;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.resume.dto.response.ResumeJobMatchCreateResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeJobMatchDetailResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeJobMatchListItemResponseDTO;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.resume.service.ResumeJobMatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Resume × JD match report APIs.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "ResumeJobMatch", description = "Resume × JD match report APIs")
public class ResumeJobMatchController {

    private final ResumeJobMatchService resumeJobMatchService;
    private final UserRepository userRepository;

    @PostMapping("/resumes/{resumeExternalId}/matches")
    @Operation(summary = "Create a match report")
    public ResponseEntity<ApiResponse<ResumeJobMatchCreateResponseDTO>> createMatchReport(
            Authentication authentication,
            @PathVariable("resumeExternalId") UUID resumeExternalId,
            @Valid @RequestBody ResumeJobMatchCreateRequestDTO request) {

        Long userId = resolveUserId(authentication);
        ResumeJobMatchCreateResponseDTO response = resumeJobMatchService.createMatchReport(resumeExternalId, userId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/resumes/{resumeExternalId}/matches")
    @Operation(summary = "List match reports for a resume")
    public ResponseEntity<ApiResponse<Page<ResumeJobMatchListItemResponseDTO>>> listMatchReports(
            Authentication authentication,
            @PathVariable("resumeExternalId") UUID resumeExternalId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        Long userId = resolveUserId(authentication);
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<ResumeJobMatchListItemResponseDTO> result = resumeJobMatchService.listMatchReports(resumeExternalId, userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/resume-matches/{matchReportId}")
    @Operation(summary = "Get match report detail")
    public ResponseEntity<ApiResponse<ResumeJobMatchDetailResponseDTO>> getMatchReport(
            Authentication authentication,
            @PathVariable("matchReportId") Long matchReportId) {

        Long userId = resolveUserId(authentication);
        ResumeJobMatchDetailResponseDTO result = resumeJobMatchService.getMatchReport(matchReportId, userId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @DeleteMapping("/resume-matches/{matchReportId}")
    @Operation(summary = "Delete match report (soft delete)")
    public ResponseEntity<Void> deleteMatchReport(
            Authentication authentication,
            @PathVariable("matchReportId") Long matchReportId) {

        Long userId = resolveUserId(authentication);
        resumeJobMatchService.deleteMatchReport(matchReportId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Resolves the authenticated principal to the internal user id.
     *
     * @param authentication authenticated principal
     * @return internal user id
     */
    private Long resolveUserId(Authentication authentication) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("USER_003", "User not found"))
                .getId();
    }
}
