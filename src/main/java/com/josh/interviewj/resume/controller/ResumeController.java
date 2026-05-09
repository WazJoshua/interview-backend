package com.josh.interviewj.resume.controller;

import com.josh.interviewj.resume.dto.request.ResumeQueryDTO;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.resume.dto.response.ResumeAnalysisResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeDetailResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeParseResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeUploadResponseDTO;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.resume.service.ResumeService;
import com.josh.interviewj.resume.service.ResumeAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.UUID;

/**
 * Resume management endpoints, including upload, retrieval, deletion, and analysis orchestration.
 */
@RestController
@RequestMapping("/api/v1/resumes")
@RequiredArgsConstructor
@Tag(name = "Resume", description = "Resume management APIs")
public class ResumeController {

    private final ResumeService resumeService;
    private final ResumeAnalysisService resumeAnalysisService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * Upload a resume file for the current user.
     *
     * @param authentication authenticated principal
     * @param file resume file
     * @param targetJob optional target job
     * @return created response with Location header
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload resume", description = "Upload a resume file for the current user")
    public ResponseEntity<ApiResponse<ResumeUploadResponseDTO>> uploadResume(
            Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "targetJob", required = false) String targetJob) {

        String username = authentication.getName();
        ResumeUploadResponseDTO response = resumeService.uploadResume(username, file, targetJob);

        URI location = URI.create("/api/v1/resumes/" + response.getId());
        return ResponseEntity.created(location)
                .body(ApiResponse.created("Resume uploaded successfully", response));
    }

    /**
     * Get resume detail for the current user.
     *
     * @param authentication authenticated principal
     * @param id resume external UUID
     * @return detail response
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get resume detail", description = "Get resume detail including parsed content")
    public ResponseEntity<ApiResponse<ResumeDetailResponseDTO>> getResumeDetail(
            Authentication authentication,
            @PathVariable("id") UUID id) {

        String username = authentication.getName();
        ResumeDetailResponseDTO detail = resumeService.getResumeDetail(username, id);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    /**
     * Trigger parsing for a resume.
     *
     * @param authentication authenticated principal
     * @param id resume external UUID
     * @return parse trigger response
     */
    @PostMapping("/{id}/parse")
    @Operation(summary = "Trigger resume parsing")
    public ResponseEntity<ApiResponse<ResumeParseResponseDTO>> triggerParse(
            Authentication authentication,
            @PathVariable("id") UUID id) {

        String username = authentication.getName();
        ResumeParseResponseDTO response = resumeService.triggerParse(username, id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get a paginated list of resumes for the current user.
     *
     * @param authentication authenticated principal
     * @param query query parameters
     * @return paginated result
     */
    @GetMapping
    @Operation(summary = "Get resume list", description = "Get a paginated list of resumes for the current user")
    public ResponseEntity<ApiResponse<Page<ResumeResponseDTO>>> getResumes(
            Authentication authentication,
            @ModelAttribute ResumeQueryDTO query) {

        String username = authentication.getName();
        Page<ResumeResponseDTO> page = resumeService.getResumes(username, query);

        return ResponseEntity.ok(ApiResponse.success(page));
    }

    /**
     * Soft delete a resume for the current user.
     *
     * @param authentication authenticated principal
     * @param id resume external UUID
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete resume", description = "Soft delete a resume for the current user")
    public ResponseEntity<Void> deleteResume(
            Authentication authentication,
            @PathVariable("id") UUID id) {

        String username = authentication.getName();
        resumeService.deleteResume(username, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Trigger analysis for a resume.
     *
     * @param authentication authenticated principal
     * @param resumeExternalId resume external UUID
     * @param request analysis request
     * @return analysis result
     */
    @PostMapping("/{resumeExternalId}/analysis")
    @Operation(summary = "Trigger resume analysis (pure, no JD)")
    public ResponseEntity<ApiResponse<Long>> triggerAnalysis(
            Authentication authentication,
            @PathVariable("resumeExternalId") UUID resumeExternalId,
            @RequestBody(required = false) String request) {

        String username = authentication.getName();
        Long userId = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("USER_003", "User not found"))
                .getId();
        enforceNoLegacyJobFields(request);
        Long reportId = resumeAnalysisService.triggerAnalysis(resumeExternalId, userId);
        return ResponseEntity.ok(ApiResponse.success(reportId));
    }

    /**
     * Get analysis result for a resume.
     *
     * @param authentication authenticated principal
     * @param resumeExternalId resume external UUID
     * @return analysis result
     */
    @GetMapping("/{resumeExternalId}/analysis")
    @Operation(summary = "Get resume analysis report")
    public ResponseEntity<ApiResponse<ResumeAnalysisResponseDTO>> getAnalysisReport(
            Authentication authentication,
            @PathVariable("resumeExternalId") UUID resumeExternalId) {

        String username = authentication.getName();
        Long userId = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("USER_003", "User not found"))
                .getId();
        ResumeAnalysisResponseDTO report = resumeAnalysisService.getAnalysisReport(resumeExternalId, userId);
        return ResponseEntity.ok(ApiResponse.success(report));
    }

    /**
     * Rejects legacy job fields that are no longer supported by the analysis trigger endpoint.
     *
     * @param requestBody raw request body
     */
    private void enforceNoLegacyJobFields(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            return;
        }

        JsonNode request;
        try {
            request = objectMapper.readTree(requestBody);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.RESUME_015, "Request body must be valid JSON");
        }

        if (request == null || request.isNull() || request.isMissingNode()) {
            return;
        }
        if (!request.isObject()) {
            throw new BusinessException(ErrorCode.RESUME_015, "Request body must be a JSON object");
        }
        if (request.size() == 0) {
            return;
        }

        String[] deprecatedKeys = {
                "targetJobTitle",
                "targetJobDescription",
                "jobTitle",
                "jobDescription",
                "targetJob"
        };
        for (String key : deprecatedKeys) {
            if (request.has(key)) {
                throw new BusinessException(ErrorCode.RESUME_015, "JD fields are no longer accepted for resume analysis");
            }
        }

        throw new BusinessException(ErrorCode.RESUME_015, "Request body is no longer accepted for resume analysis");
    }
}
