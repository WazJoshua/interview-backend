package com.josh.interviewj.knowledgebase.controller;

import com.josh.interviewj.knowledgebase.dto.request.KbDocumentQueryRequest;
import com.josh.interviewj.knowledgebase.dto.request.KnowledgeBaseCreateRequest;
import com.josh.interviewj.knowledgebase.dto.request.KnowledgeBaseQueryRequest;
import com.josh.interviewj.knowledgebase.dto.request.KnowledgeBaseUpdateRequest;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentArtifactResponse;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentDetailResponse;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentResponse;
import com.josh.interviewj.knowledgebase.dto.response.KnowledgeBaseReindexResponse;
import com.josh.interviewj.knowledgebase.dto.response.KnowledgeBaseResponse;
import com.josh.interviewj.knowledgebase.dto.response.KnowledgeBaseStatsResponse;
import com.josh.interviewj.knowledgebase.model.ChunkStrategy;
import com.josh.interviewj.knowledgebase.model.KbDocumentArtifactType;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseLifecycleService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseReindexService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseService;
import com.josh.interviewj.knowledgebase.service.KnowledgeBaseStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Knowledge base APIs including CRUD-lite and document ingestion.
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
@Validated
@Tag(name = "KnowledgeBase", description = "Knowledge base APIs")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseLifecycleService knowledgeBaseLifecycleService;
    private final KnowledgeBaseStatsService knowledgeBaseStatsService;
    private final KnowledgeBaseReindexService knowledgeBaseReindexService;

    /**
     * Create a knowledge base for current user.
     */
    @PostMapping
    @Operation(summary = "Create knowledge base")
    public ResponseEntity<ApiResponse<KnowledgeBaseResponse>> createKnowledgeBase(
            Authentication authentication,
            @Valid @RequestBody KnowledgeBaseCreateRequest request) {

        String username = authentication.getName();
        KnowledgeBaseResponse response = knowledgeBaseService.createKnowledgeBase(username, request);

        URI location = URI.create("/api/v1/knowledge-bases/" + response.getId());
        return ResponseEntity.created(location)
                .body(ApiResponse.created("Knowledge base created successfully", response));
    }

    /**
     * List knowledge bases for current user.
     */
    @GetMapping
    @Operation(summary = "List knowledge bases")
    public ResponseEntity<ApiResponse<Page<KnowledgeBaseResponse>>> getKnowledgeBases(
            Authentication authentication,
            @Valid @ModelAttribute KnowledgeBaseQueryRequest request) {

        String username = authentication.getName();
        Page<KnowledgeBaseResponse> response = knowledgeBaseService.getKnowledgeBases(username, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get a knowledge base detail by external id.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get knowledge base detail")
    public ResponseEntity<ApiResponse<KnowledgeBaseResponse>> getKnowledgeBase(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId) {

        String username = authentication.getName();
        KnowledgeBaseResponse response = knowledgeBaseService.getKnowledgeBase(username, kbExternalId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Updates mutable knowledge base fields for the current user.
     */
    @PutMapping("/{id}")
    @Operation(summary = "Update knowledge base")
    public ResponseEntity<ApiResponse<KnowledgeBaseResponse>> updateKnowledgeBase(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId,
            @Valid @RequestBody KnowledgeBaseUpdateRequest request) {

        String username = authentication.getName();
        KnowledgeBaseResponse response = knowledgeBaseLifecycleService.updateKnowledgeBase(username, kbExternalId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Soft-deletes the target knowledge base and hard-cleans its child resources.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete knowledge base")
    public ResponseEntity<Void> deleteKnowledgeBase(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId) {

        String username = authentication.getName();
        knowledgeBaseLifecycleService.deleteKnowledgeBase(username, kbExternalId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Upload a document and enqueue async ingestion.
     */
    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload knowledge base document")
    public ResponseEntity<ApiResponse<KbDocumentResponse>> uploadDocument(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chunkStrategy", defaultValue = "FIXED_SIZE") String chunkStrategy) {

        if (!ChunkStrategy.FIXED_SIZE.name().equals(chunkStrategy)) {
            throw new BusinessException("VALIDATION_ERROR", "chunkStrategy must be FIXED_SIZE in Phase 1");
        }

        String username = authentication.getName();
        KbDocumentResponse response = knowledgeBaseService.uploadDocument(
                username,
                kbExternalId,
                file,
                ChunkStrategy.FIXED_SIZE
        );

        ApiResponse<KbDocumentResponse> acceptedResponse = ApiResponse.<KbDocumentResponse>builder()
                .code(202)
                .message("Document uploaded, processing in background")
                .data(response)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(generateRequestId())
                .build();

        return ResponseEntity.accepted().body(acceptedResponse);
    }

    /**
     * List documents in a knowledge base.
     */
    @GetMapping("/{id}/documents")
    @Operation(summary = "List knowledge base documents")
    public ResponseEntity<ApiResponse<Page<KbDocumentResponse>>> getDocuments(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId,
            @Valid @ModelAttribute KbDocumentQueryRequest request) {

        String username = authentication.getName();
        Page<KbDocumentResponse> response = knowledgeBaseService.getDocuments(username, kbExternalId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}/documents/{docExternalId}")
    @Operation(summary = "Get knowledge base document detail")
    public ResponseEntity<ApiResponse<KbDocumentDetailResponse>> getDocumentDetail(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId,
            @PathVariable("docExternalId") UUID docExternalId) {

        String username = authentication.getName();
        KbDocumentDetailResponse response = knowledgeBaseService.getDocumentDetail(username, kbExternalId, docExternalId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Deletes one document and its derived resources from the target knowledge base.
     */
    @DeleteMapping("/{id}/documents/{docExternalId}")
    @Operation(summary = "Delete knowledge base document")
    public ResponseEntity<Void> deleteDocument(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId,
            @PathVariable("docExternalId") UUID docExternalId) {

        String username = authentication.getName();
        knowledgeBaseLifecycleService.deleteDocument(username, kbExternalId, docExternalId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/documents/{docExternalId}/artifacts/{artifactType}")
    @Operation(summary = "Get knowledge base document artifact")
    public ResponseEntity<ApiResponse<KbDocumentArtifactResponse>> getDocumentArtifact(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId,
            @PathVariable("docExternalId") UUID docExternalId,
            @PathVariable("artifactType") KbDocumentArtifactType artifactType) {

        String username = authentication.getName();
        KbDocumentArtifactResponse response = knowledgeBaseService.getDocumentArtifact(
                username,
                kbExternalId,
                docExternalId,
                artifactType
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Returns the live statistics snapshot for the target knowledge base.
     */
    @GetMapping("/{id}/stats")
    @Operation(summary = "Get knowledge base stats")
    public ResponseEntity<ApiResponse<KnowledgeBaseStatsResponse>> getKnowledgeBaseStats(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId) {

        String username = authentication.getName();
        KnowledgeBaseStatsResponse response = knowledgeBaseStatsService.getStats(username, kbExternalId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Triggers a full document reset and re-ingestion cycle for the target knowledge base.
     */
    @PostMapping("/{id}/reindex")
    @Operation(summary = "Reindex knowledge base")
    public ResponseEntity<ApiResponse<KnowledgeBaseReindexResponse>> reindexKnowledgeBase(
            Authentication authentication,
            @PathVariable("id") UUID kbExternalId) {

        String username = authentication.getName();
        KnowledgeBaseReindexResponse response = knowledgeBaseReindexService.reindex(username, kbExternalId);
        ApiResponse<KnowledgeBaseReindexResponse> acceptedResponse = ApiResponse.<KnowledgeBaseReindexResponse>builder()
                .code(202)
                .message("Knowledge base reindex accepted")
                .data(response)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(generateRequestId())
                .build();
        return ResponseEntity.accepted().body(acceptedResponse);
    }

    /**
     * Generates a short request id used in accepted responses for async document ingestion.
     *
     * @return request id
     */
    private String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
