package com.josh.interviewj.ragqa.controller;

import com.josh.interviewj.chat.model.ChatSessionStatus;
import com.josh.interviewj.chat.service.ChatSessionQueryService;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseChatMessageTimelineResponse;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseChatSessionListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
@Validated
@Tag(name = "RagQa", description = "Knowledge-base RAG QA chat session APIs")
public class KnowledgeBaseChatSessionController {

    private final ChatSessionQueryService chatSessionQueryService;

    @GetMapping("/{id}/sessions")
    @Operation(summary = "List chat sessions for a knowledge base")
    public ResponseEntity<ApiResponse<KnowledgeBaseChatSessionListResponse>> listSessions(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) ChatSessionStatus status
    ) {
        validatePagination(page, size);
        return ResponseEntity.ok(ApiResponse.success(chatSessionQueryService.listKnowledgeBaseSessions(
                authentication.getName(),
                kbExternalId,
                page,
                size,
                status
        )));
    }

    @GetMapping("/{id}/sessions/{sessionId}/messages")
    @Operation(summary = "Get chat session timeline for a knowledge base")
    public ResponseEntity<ApiResponse<KnowledgeBaseChatMessageTimelineResponse>> getMessages(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId,
            @PathVariable UUID sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(chatSessionQueryService.getKnowledgeBaseTimeline(
                authentication.getName(),
                kbExternalId,
                sessionId
        )));
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException("VALIDATION_ERROR", "Request validation failed");
        }
    }
}
