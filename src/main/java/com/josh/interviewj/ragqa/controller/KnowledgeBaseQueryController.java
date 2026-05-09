package com.josh.interviewj.ragqa.controller;

import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.ragqa.dto.request.KnowledgeBaseQueryAskRequest;
import com.josh.interviewj.ragqa.dto.response.KnowledgeBaseQueryResponse;
import com.josh.interviewj.ragqa.service.KnowledgeBaseQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * RAG QA query API bound to a specific knowledge base.
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
@Validated
@Tag(name = "RagQa", description = "Knowledge-base RAG QA APIs")
public class KnowledgeBaseQueryController {

    private final KnowledgeBaseQueryService knowledgeBaseQueryService;

    /**
     * Query a knowledge base with user question.
     */
    @PostMapping("/{id}/query")
    @Operation(summary = "Query knowledge base")
    public ResponseEntity<ApiResponse<KnowledgeBaseQueryResponse>> queryKnowledgeBase(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId,
            @Valid @RequestBody KnowledgeBaseQueryAskRequest request) {

        String username = authentication.getName();
        KnowledgeBaseQueryResponse response = knowledgeBaseQueryService.askQuestion(username, kbExternalId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
