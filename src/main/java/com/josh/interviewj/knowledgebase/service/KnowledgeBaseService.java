package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.knowledgebase.dto.request.KbDocumentQueryRequest;
import com.josh.interviewj.knowledgebase.dto.request.KnowledgeBaseCreateRequest;
import com.josh.interviewj.knowledgebase.dto.request.KnowledgeBaseQueryRequest;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentArtifactResponse;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentDetailResponse;
import com.josh.interviewj.knowledgebase.dto.response.KbDocumentResponse;
import com.josh.interviewj.knowledgebase.dto.response.KnowledgeBaseResponse;
import com.josh.interviewj.knowledgebase.model.KbDocumentArtifact;
import com.josh.interviewj.knowledgebase.model.KbDocumentArtifactType;
import com.josh.interviewj.knowledgebase.model.KbDocument;
import com.josh.interviewj.knowledgebase.outbox.KbDocumentOutbox;
import com.josh.interviewj.knowledgebase.model.KnowledgeBase;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.knowledgebase.model.ChunkStrategy;
import com.josh.interviewj.knowledgebase.model.KbDocumentStatus;
import com.josh.interviewj.knowledgebase.model.KnowledgeBaseStatus;
import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.knowledgebase.repository.KbDocumentRepository;
import com.josh.interviewj.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.List;

/**
 * Manages knowledge-base creation, document registration, and document listing.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "doc", "docx", "md", "html", "htm", "txt");
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/markdown",
            "text/x-markdown",
            "text/html",
            "text/plain"
    );

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbDocumentRepository kbDocumentRepository;
    private final KbDocumentOutboxRepository kbDocumentOutboxRepository;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final KnowledgeBaseStorageService knowledgeBaseStorageService;
    private final KbDocumentArtifactService kbDocumentArtifactService;
    private final KnowledgeBaseEmbeddingConfigService knowledgeBaseEmbeddingConfigService;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new knowledge base for the specified user.
     *
     * @param username current username
     * @param request create request
     * @return created knowledge-base payload
     */
    @Transactional
    public KnowledgeBaseResponse createKnowledgeBase(String username, KnowledgeBaseCreateRequest request) {
        User user = knowledgeBaseAccessService.requireUser(username);
        KnowledgeBaseEmbeddingConfigService.KnowledgeBaseEmbeddingConfig embeddingConfig =
                knowledgeBaseEmbeddingConfigService.getCurrentDocumentEmbedding();

        KnowledgeBase entity = KnowledgeBase.builder()
                .userId(user.getId())
                .name(request.getName())
                .description(request.getDescription())
                .embeddingModel(embeddingConfig.model())
                .vectorDimension(embeddingConfig.dimension())
                .documentCount(0)
                .totalChunks(0)
                .version(1)
                .isPublic(false)
                .status(KnowledgeBaseStatus.ACTIVE)
                .build();

        return toKnowledgeBaseResponse(knowledgeBaseRepository.save(entity));
    }

    /**
     * Lists knowledge bases visible to the specified user.
     *
     * @param username current username
     * @param request query filters and paging
     * @return paged knowledge-base summaries
     */
    public Page<KnowledgeBaseResponse> getKnowledgeBases(String username, KnowledgeBaseQueryRequest request) {
        User user = knowledgeBaseAccessService.requireUser(username);
        int page = request == null ? 0 : request.getPage();
        int size = request == null ? 20 : request.getSize();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        KnowledgeBaseStatus status = null;
        if (request != null && request.getStatus() != null && !request.getStatus().isBlank()) {
            status = KnowledgeBaseStatus.valueOf(request.getStatus().trim().toUpperCase(Locale.ROOT));
        }

        Page<KnowledgeBase> pageResult = status == null
                ? knowledgeBaseRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                        user.getId(),
                        List.of(KnowledgeBaseStatus.ACTIVE, KnowledgeBaseStatus.ARCHIVED),
                        pageable
                )
                : knowledgeBaseRepository.findByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), status, pageable);

        return pageResult.map(this::toKnowledgeBaseResponse);
    }

    /**
     * Loads a single accessible knowledge base.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @return knowledge-base detail
     */
    public KnowledgeBaseResponse getKnowledgeBase(String username, UUID kbExternalId) {
        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireReadableKnowledgeBase(username, kbExternalId);
        return toKnowledgeBaseResponse(knowledgeBase);
    }

    /**
     * Registers an uploaded document and creates the outbox row for async ingestion.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @param file uploaded file
     * @param chunkStrategy requested chunk strategy
     * @return registered document payload
     */
    @Transactional
    public KbDocumentResponse uploadDocument(String username, UUID kbExternalId, MultipartFile file, ChunkStrategy chunkStrategy) {
        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireDocumentMutationKnowledgeBase(username, kbExternalId);
        validateFile(file);
        KnowledgeBase lockedKnowledgeBase = knowledgeBaseRepository.findByIdForUpdate(knowledgeBase.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_001, "知识库不存在"));
        validateLockedDocumentMutationKnowledgeBase(lockedKnowledgeBase);

        if (chunkStrategy != null && chunkStrategy != ChunkStrategy.FIXED_SIZE) {
            throw new BusinessException("VALIDATION_ERROR", "chunkStrategy must be FIXED_SIZE in Phase 1");
        }

        // Persist the physical file first so the DB record always points to a real storage object.
        String fileUrl = knowledgeBaseStorageService.store(file);
        String fileType = normalizeContentType(file.getContentType());
        String originalFileName = normalizeFileName(file.getOriginalFilename());
        ChunkStrategy effectiveChunkStrategy = chunkStrategy == null ? ChunkStrategy.FIXED_SIZE : chunkStrategy;

        KbDocument document = KbDocument.builder()
                .kbId(lockedKnowledgeBase.getId())
                .fileName(originalFileName)
                .fileType(fileType)
                .fileSize(file.getSize())
                .fileUrl(fileUrl)
                .contentHash(calculateSha256(file))
                .chunkCount(0)
                .expectedChunkCount(0)
                .embeddedChunkCount(0)
                .chunkStrategy(effectiveChunkStrategy)
                .version(1)
                .status(KbDocumentStatus.PENDING)
                .build();
        document = kbDocumentRepository.save(document);

        // Create the outbox row in the same transaction so asynchronous ingestion cannot miss the new document.
        kbDocumentOutboxRepository.save(KbDocumentOutbox.builder()
                .kbId(lockedKnowledgeBase.getId())
                .documentId(document.getId())
                .status(OutboxStatus.NEW)
                .retryCount(0)
                .build());

        lockedKnowledgeBase.setDocumentCount((lockedKnowledgeBase.getDocumentCount() == null ? 0 : lockedKnowledgeBase.getDocumentCount()) + 1);
        knowledgeBaseRepository.save(lockedKnowledgeBase);

        return toDocumentResponse(document, lockedKnowledgeBase.getExternalId());
    }

    /**
     * Lists documents under an accessible knowledge base.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @param request query filters and paging
     * @return paged document summaries
     */
    public Page<KbDocumentResponse> getDocuments(String username, UUID kbExternalId, KbDocumentQueryRequest request) {
        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireReadableKnowledgeBase(username, kbExternalId);
        int page = request == null ? 0 : request.getPage();
        int size = request == null ? 20 : request.getSize();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        KbDocumentStatus status = null;
        if (request != null && request.getStatus() != null && !request.getStatus().isBlank()) {
            status = KbDocumentStatus.valueOf(request.getStatus().trim().toUpperCase(Locale.ROOT));
        }

        Page<KbDocument> documents = status == null
                ? kbDocumentRepository.findByKbIdOrderByCreatedAtDesc(knowledgeBase.getId(), pageable)
                : kbDocumentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(knowledgeBase.getId(), status, pageable);

        return documents.map(document -> toDocumentResponse(document, knowledgeBase.getExternalId()));
    }

    /**
     * Loads document detail together with review artifact presence metadata.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @param docExternalId document external id
     * @return document detail response
     */
    public KbDocumentDetailResponse getDocumentDetail(String username, UUID kbExternalId, UUID docExternalId) {
        KbDocument document = knowledgeBaseAccessService.requireReadableDocument(username, kbExternalId, docExternalId);
        Optional<KbDocumentArtifact> artifact = kbDocumentArtifactService.findArtifact(
                document.getId(),
                KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT
        );
        return toDocumentDetailResponse(document, kbExternalId, buildArtifactSummary(artifact));
    }

    /**
     * Loads one concrete document artifact under the target knowledge base.
     *
     * @param username current username
     * @param kbExternalId knowledge base external id
     * @param docExternalId document external id
     * @param artifactType artifact type
     * @return artifact response
     */
    public KbDocumentArtifactResponse getDocumentArtifact(
            String username,
            UUID kbExternalId,
            UUID docExternalId,
            KbDocumentArtifactType artifactType
    ) {
        KbDocument document = knowledgeBaseAccessService.requireReadableDocument(username, kbExternalId, docExternalId);
        KbDocumentArtifact artifact = kbDocumentArtifactService.findArtifact(document.getId(), artifactType)
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_002, "文档不存在"));
        return toArtifactResponse(artifact, true);
    }

    /**
     * Validates file size, extension, and content type before registration.
     *
     * @param file uploaded file
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_001, "不支持的文档格式");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.RESUME_002, "文件大小超过限制");
        }

        String fileName = normalizeFileName(file.getOriginalFilename());
        String extension = resolveExtension(fileName);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.RESUME_001, "不支持的文档格式");
        }

        String contentType = normalizeContentType(file.getContentType());
        if (contentType != null && !contentType.isBlank() && !SUPPORTED_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.RESUME_001, "不支持的文档格式");
        }
    }

    /**
     * Computes a SHA-256 hash for content-deduplication and traceability.
     *
     * @param file uploaded file
     * @return lowercase SHA-256 hash
     */
    private String calculateSha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(file.getBytes()));
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.FILE_001, "文件上传失败，请稍后重试", ex);
        }
    }

    /**
     * Parses a simple {@code field,direction} sort expression.
     *
     * @param sortValue sort expression
     * @return resolved sort
     */
    private Sort parseSort(String sortValue) {
        if (sortValue == null || sortValue.isBlank()) {
            return Sort.by("createdAt").descending();
        }
        String[] parts = sortValue.split(",");
        String property = parts[0];
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1])
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, property);
    }

    /**
     * Normalizes an uploaded filename and falls back to a placeholder when missing.
     *
     * @param fileName raw filename
     * @return normalized filename
     */
    private String normalizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "document.bin";
        }
        return fileName.strip();
    }

    /**
     * Extracts a lowercase extension from a normalized filename.
     *
     * @param fileName normalized filename
     * @return extension or empty string
     */
    private String resolveExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes content type text for validation and persistence.
     *
     * @param contentType raw content type
     * @return normalized content type
     */
    private String normalizeContentType(String contentType) {
        return contentType == null ? null : contentType.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Rejects document mutations when the locked knowledge base is no longer writable.
     *
     * @param knowledgeBase locked knowledge base
     */
    private void validateLockedDocumentMutationKnowledgeBase(KnowledgeBase knowledgeBase) {
        if (knowledgeBase.getStatus() == KnowledgeBaseStatus.DELETED) {
            throw new BusinessException(ErrorCode.KB_001, "知识库不存在");
        }
        if (knowledgeBase.getStatus() == KnowledgeBaseStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.KB_003, "知识库当前状态不允许此操作");
        }
    }

    /**
     * Maps a knowledge-base entity to its API response DTO.
     *
     * @param entity knowledge-base entity
     * @return response DTO
     */
    private KnowledgeBaseResponse toKnowledgeBaseResponse(KnowledgeBase entity) {
        return KnowledgeBaseResponse.builder()
                .id(entity.getExternalId())
                .name(entity.getName())
                .description(entity.getDescription())
                .embeddingModel(entity.getEmbeddingModel())
                .vectorDimension(entity.getVectorDimension())
                .documentCount(entity.getDocumentCount())
                .totalChunks(entity.getTotalChunks())
                .version(entity.getVersion())
                .isPublic(entity.getIsPublic())
                .status(entity.getStatus())
                .indexingStatus(entity.getIndexingStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * Maps a document entity to its API response DTO.
     *
     * @param entity document entity
     * @param kbExternalId parent knowledge-base external id
     * @return response DTO
     */
    private KbDocumentResponse toDocumentResponse(KbDocument entity, UUID kbExternalId) {
        return KbDocumentResponse.builder()
                .id(entity.getExternalId())
                .kbId(kbExternalId)
                .fileName(entity.getFileName())
                .fileType(entity.getFileType())
                .fileSize(entity.getFileSize())
                .chunkCount(entity.getChunkCount())
                .chunkStrategy(entity.getChunkStrategy())
                .status(entity.getStatus())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt())
                .processedAt(entity.getProcessedAt())
                .build();
    }

    private KbDocumentDetailResponse toDocumentDetailResponse(
            KbDocument entity,
            UUID kbExternalId,
            KbDocumentArtifactResponse artifactSummary
    ) {
        return KbDocumentDetailResponse.builder()
                .id(entity.getExternalId())
                .kbId(kbExternalId)
                .fileName(entity.getFileName())
                .fileType(entity.getFileType())
                .fileSize(entity.getFileSize())
                .chunkCount(entity.getChunkCount())
                .expectedChunkCount(entity.getExpectedChunkCount())
                .embeddedChunkCount(entity.getEmbeddedChunkCount())
                .chunkStrategy(entity.getChunkStrategy())
                .status(entity.getStatus())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .processedAt(entity.getProcessedAt())
                .artifactSummary(artifactSummary)
                .build();
    }

    private KbDocumentArtifactResponse buildArtifactSummary(Optional<KbDocumentArtifact> artifact) {
        if (artifact.isEmpty()) {
            return KbDocumentArtifactResponse.builder()
                    .artifactType(KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT)
                    .exists(false)
                    .metadata(Map.of())
                    .build();
        }
        return toArtifactResponse(artifact.get(), false);
    }

    private KbDocumentArtifactResponse toArtifactResponse(KbDocumentArtifact artifact, boolean includeContent) {
        return KbDocumentArtifactResponse.builder()
                .artifactType(artifact.getArtifactType())
                .exists(true)
                .content(includeContent ? artifact.getContent() : null)
                .metadata(parseJsonMap(artifact.getMetadata()))
                .updatedAt(artifact.getUpdatedAt())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            throw new RuntimeException("知识库 artifact 元数据解析失败", ex);
        }
    }
}
