package com.josh.interviewj.interview.controller;

import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.interview.dto.request.CompleteInterviewRequest;
import com.josh.interviewj.interview.dto.request.CreateInterviewRequest;
import com.josh.interviewj.interview.dto.request.SubmitInterviewAnswerRequest;
import com.josh.interviewj.interview.dto.response.CreateInterviewResponse;
import com.josh.interviewj.interview.dto.response.DeleteInterviewResponse;
import com.josh.interviewj.interview.dto.response.InterviewDetailResponse;
import com.josh.interviewj.interview.dto.response.InterviewLifecycleResponse;
import com.josh.interviewj.interview.dto.response.InterviewListItemResponse;
import com.josh.interviewj.interview.dto.response.InterviewMessageTimelineResponse;
import com.josh.interviewj.interview.dto.response.InterviewQuestionItemResponse;
import com.josh.interviewj.interview.dto.response.InterviewReportResponse;
import com.josh.interviewj.interview.dto.response.InterviewStartResponse;
import com.josh.interviewj.interview.dto.response.SubmitInterviewAnswerResponse;
import com.josh.interviewj.interview.model.InterviewStatus;
import com.josh.interviewj.interview.service.InterviewAnswerCommandService;
import com.josh.interviewj.interview.service.InterviewLifecycleService;
import com.josh.interviewj.interview.service.InterviewQueryService;
import com.josh.interviewj.interview.service.InterviewReportService;
import com.josh.interviewj.interview.service.InterviewSessionService;
import com.josh.interviewj.interview.service.InterviewTimelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/interviews")
@RequiredArgsConstructor
@Validated
@Tag(name = "Interview", description = "Interview phase 1 APIs")
public class InterviewController {

    private final InterviewSessionService interviewSessionService;
    private final InterviewQueryService interviewQueryService;
    private final InterviewAnswerCommandService interviewAnswerCommandService;
    private final InterviewTimelineService interviewTimelineService;
    private final InterviewLifecycleService interviewLifecycleService;
    private final InterviewReportService interviewReportService;

    @PostMapping
    @Operation(summary = "Create interview")
    public ResponseEntity<ApiResponse<CreateInterviewResponse>> createInterview(
            Authentication authentication,
            @Valid @RequestBody CreateInterviewRequest request
    ) {
        CreateInterviewResponse response = interviewSessionService.createInterview(authentication.getName(), request);
        return ResponseEntity.status(201).body(ApiResponse.created("Interview created", response));
    }

    @GetMapping
    @Operation(summary = "List interviews")
    public ResponseEntity<ApiResponse<Page<InterviewListItemResponse>>> listInterviews(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) InterviewStatus status
    ) {
        validatePagination(page, size);
        return ResponseEntity.ok(ApiResponse.success(interviewSessionService.listInterviews(
                authentication.getName(),
                page,
                size,
                status
        )));
    }

    @GetMapping("/{interviewId}")
    @Operation(summary = "Get interview detail")
    public ResponseEntity<ApiResponse<InterviewDetailResponse>> getInterview(
            Authentication authentication,
            @PathVariable UUID interviewId
    ) {
        return ResponseEntity.ok(ApiResponse.success(interviewSessionService.getInterviewDetail(
                authentication.getName(),
                interviewId
        )));
    }

    @GetMapping("/{interviewId}/questions")
    @Operation(summary = "Get interview questions")
    public ResponseEntity<ApiResponse<java.util.List<InterviewQuestionItemResponse>>> getQuestions(
            Authentication authentication,
            @PathVariable UUID interviewId
    ) {
        return ResponseEntity.ok(ApiResponse.success(interviewQueryService.getQuestions(
                authentication.getName(),
                interviewId
        ).questions()));
    }

    @GetMapping("/{interviewId}/messages")
    @Operation(summary = "Get interview timeline")
    public ResponseEntity<ApiResponse<InterviewMessageTimelineResponse>> getMessages(
            Authentication authentication,
            @PathVariable UUID interviewId
    ) {
        return ResponseEntity.ok(ApiResponse.success(interviewTimelineService.getTimeline(
                authentication.getName(),
                interviewId
        )));
    }

    @PostMapping("/{interviewId}/start")
    @Operation(summary = "Start interview")
    public ResponseEntity<ApiResponse<InterviewStartResponse>> startInterview(
            Authentication authentication,
            @PathVariable UUID interviewId
    ) {
        return ResponseEntity.ok(ApiResponse.success("Interview started", interviewSessionService.startInterview(
                authentication.getName(),
                interviewId
        )));
    }

    @PostMapping("/{interviewId}/questions/{questionId}/answer")
    @Operation(summary = "Submit interview answer")
    public ResponseEntity<ApiResponse<SubmitInterviewAnswerResponse>> submitAnswer(
            Authentication authentication,
            @PathVariable UUID interviewId,
            @PathVariable UUID questionId,
            @Valid @RequestBody SubmitInterviewAnswerRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success("Answer accepted", interviewAnswerCommandService.submitAnswer(
                authentication.getName(),
                interviewId,
                questionId,
                request
        )));
    }

    @PostMapping("/{interviewId}/end")
    @Operation(summary = "End interview")
    public ResponseEntity<ApiResponse<InterviewLifecycleResponse>> endInterview(
            Authentication authentication,
            @PathVariable UUID interviewId,
            @Valid @RequestBody CompleteInterviewRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success("Interview completed", interviewLifecycleService.endInterview(
                authentication.getName(),
                interviewId,
                request
        )));
    }

    @DeleteMapping("/{interviewId}")
    @Operation(summary = "Delete interview")
    public ResponseEntity<ApiResponse<DeleteInterviewResponse>> deleteInterview(
            Authentication authentication,
            @PathVariable UUID interviewId
    ) {
        return ResponseEntity.ok(ApiResponse.success("Interview deleted", interviewLifecycleService.deleteInterview(
                authentication.getName(),
                interviewId
        )));
    }

    @GetMapping("/{interviewId}/report")
    @Operation(summary = "Get interview report")
    public ResponseEntity<ApiResponse<InterviewReportResponse>> getReport(
            Authentication authentication,
            @PathVariable UUID interviewId
    ) {
        return ResponseEntity.ok(ApiResponse.success(interviewReportService.getReport(
                authentication.getName(),
                interviewId
        )));
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException("VALIDATION_ERROR", "Request validation failed");
        }
    }
}
