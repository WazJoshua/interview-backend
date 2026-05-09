package com.josh.interviewj.resume.service;

import com.josh.interviewj.resume.dto.response.ResumeDetailResponseDTO;
import com.josh.interviewj.resume.dto.request.ResumeQueryDTO;
import com.josh.interviewj.resume.dto.response.ResumeParseResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeResponseDTO;
import com.josh.interviewj.resume.dto.response.ResumeUploadResponseDTO;
import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.resume.outbox.ResumeParseOutbox;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeRepository;
import com.josh.interviewj.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.persistence.criteria.Predicate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ResumeService {

    private static final long MAX_SIZE_MB_10 = 10L * 1024 * 1024;
    private static final long MAX_SIZE_MB_5 = 5L * 1024 * 1024;
    private static final Map<String, SupportedFileType> SUPPORTED_FILE_TYPES = Map.of(
            "pdf", new SupportedFileType(Set.of("application/pdf"), MAX_SIZE_MB_10, "application/pdf"),
            "doc", new SupportedFileType(Set.of("application/msword"), MAX_SIZE_MB_10, "application/msword"),
            "docx", new SupportedFileType(
                    Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    MAX_SIZE_MB_10,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "txt", new SupportedFileType(Set.of("text/plain"), MAX_SIZE_MB_5, "text/plain"),
            "rtf", new SupportedFileType(Set.of("application/rtf", "text/rtf", "application/x-rtf"), MAX_SIZE_MB_10,
                    "application/rtf"),
            "md", new SupportedFileType(Set.of("text/markdown", "text/x-markdown"), MAX_SIZE_MB_5, "text/markdown"));

    private final ResumeRepository resumeRepository;
    private final UserRepository userRepository;
    private final ResumeStorageService resumeStorageService;
    private final ResumeParseOutboxRepository resumeParseOutboxRepository;

    private final ObjectMapper objectMapper;

    /**
     * Query resumes for the given user with pagination, sorting and optional status filter.
     *
     * @param username the current username
     * @param query query parameters (page/size/sort/status)
     * @return a page of resume summary DTOs
     */
    public Page<ResumeResponseDTO> getResumes(String username, ResumeQueryDTO query) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        ResumeStatus statusFilter = parseAndNormalizeStatusFilter(query);

        Sort sort = parseSort(query.getSort());
        Pageable pageable = PageRequest.of(query.getPage(), query.getSize(), sort);

        Specification<Resume> spec = (root, cq, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("userId"), user.getId()));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (statusFilter != null) {
                predicates.add(cb.equal(root.get("status"), statusFilter));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Page<Resume> resumePage = resumeRepository.findAll(spec, pageable);

        return resumePage.map(this::mapToResponseDTO);
    }

    /**
     * Upload a resume file for the current user.
     *
     * <p>The resume will be created with {@link ResumeStatus#UPLOADED}. Parsing is triggered explicitly via
     * {@link #triggerParse(String, UUID)} to avoid implicit resource consumption.</p>
     *
     * @param username the current username
     * @param file resume file
     * @param targetJob optional target job
     * @return upload response DTO
     * @throws BusinessException for validation/duplicate/file errors
     */
    @Transactional
    public ResumeUploadResponseDTO uploadResume(String username, MultipartFile file, String targetJob) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        // 1) Validate file format and size.
        String extension = validateFile(file);

        // 2) Dedupe by content hash at user scope.
        String contentHash = calculateSha256(file);
        validateDuplicateResume(user.getId(), contentHash);

        // 3) Store the file and persist Resume.
        String originalFileName = normalizeFileName(file.getOriginalFilename());
        String fileUrl = resumeStorageService.store(file);

        Resume resume = Resume.builder()
                .userId(user.getId())
                .fileName(originalFileName)
                .fileUrl(fileUrl)
                .fileType(resolveFileType(file, extension))
                .fileSize(file.getSize())
                .contentHash(contentHash)
                .targetJob(normalizeTargetJob(targetJob))
                .status(ResumeStatus.UPLOADED)
                .analysisStatus(AnalysisStatus.PENDING)
                .build();

        Resume savedResume = resumeRepository.save(resume);
        return mapToUploadResponseDTO(savedResume);
    }

    /**
     * Trigger resume parsing by enqueuing a task via Outbox.
     *
     * <p>This operation is idempotent: only {@link ResumeStatus#UPLOADED} will be moved to {@link ResumeStatus#PENDING}
     * and have an outbox record created. Other statuses will be returned as-is.</p>
     *
     * @param username the current username
     * @param resumeId resume external UUID
     * @return parse trigger response including current status
     */
    @Transactional
    public ResumeParseResponseDTO triggerParse(String username, UUID resumeId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        Resume resume = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeId, user.getId())
                .orElseThrow(() -> new BusinessException("RESUME_005", "Resume not found"));

        if (resume.getStatus() != ResumeStatus.UPLOADED) {
            return ResumeParseResponseDTO.builder()
                    .id(resume.getExternalId())
                    .status(resume.getStatus())
                    .build();
        }

        int updated = resumeRepository.updateStatusWithCondition(resume.getId(), ResumeStatus.UPLOADED, ResumeStatus.PENDING);
        if (updated == 1) {
            ResumeParseOutbox outbox = ResumeParseOutbox.builder()
                    .resumeId(resume.getId())
                    .resumeExternalId(resume.getExternalId())
                    .build();
            resumeParseOutboxRepository.save(outbox);

            return ResumeParseResponseDTO.builder()
                    .id(resume.getExternalId())
                    .status(ResumeStatus.PENDING)
                    .build();
        }

        Resume current = resumeRepository.findById(resume.getId()).orElse(resume);
        return ResumeParseResponseDTO.builder()
                .id(current.getExternalId())
                .status(current.getStatus())
                .build();
    }

    /**
     * Get a resume detail for the current user (ownership enforced).
     *
     * @param username the current username
     * @param resumeId resume external UUID
     * @return detail DTO including parsed content (if available)
     * @throws BusinessException if not found
     */
    public ResumeDetailResponseDTO getResumeDetail(String username, UUID resumeId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        Resume resume = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeId, user.getId())
                .orElseThrow(() -> new BusinessException("RESUME_005", "Resume not found"));

        JsonNode parsedContent = null;
        String parsedContentRaw = resume.getParsedContent();
        String errorMessage = resume.getErrorMessage();
        if (parsedContentRaw != null && !parsedContentRaw.isBlank()) {
            try {
                // Keep API contract stable: return a JSON object, not a raw string.
                parsedContent = objectMapper.readTree(parsedContentRaw);
            } catch (Exception e) {
                // Don't fail the detail endpoint on malformed jsonb content; keep response safe.
                if (errorMessage == null || errorMessage.isBlank()) {
                    errorMessage = "Parsed content is unavailable";
                }
            }
        }

        return ResumeDetailResponseDTO.builder()
                .id(resume.getExternalId())
                .fileName(resume.getFileName())
                .fileType(resume.getFileType())
                .fileSize(resume.getFileSize())
                .targetJob(resume.getTargetJob())
                .status(resume.getStatus())
                .analysisStatus(resume.getAnalysisStatus())
                .hasAnalysis(resume.getAnalysisStatus() == AnalysisStatus.COMPLETED)
                .parsedContent(parsedContent)
                .errorMessage(errorMessage)
                .createdAt(resume.getCreatedAt())
                .updatedAt(resume.getUpdatedAt())
                .build();
    }

    /**
     * Ensure the user doesn't upload the same resume content multiple times.
     *
     * @param userId current user id
     * @param contentHash sha-256 hash of file bytes
     */
    private void validateDuplicateResume(Long userId, String contentHash) {
        if (resumeRepository.existsByUserIdAndContentHashAndDeletedAtIsNull(userId, contentHash)) {
            throw new BusinessException("RESUME_006", "Duplicate resume content is not allowed");
        }
    }

    /**
     * Soft delete a resume for the current user.
     *
     * @param username the current username
     * @param resumeId resume external UUID
     */
    @Transactional
    public void deleteResume(String username, UUID resumeId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        Resume resume = resumeRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(resumeId, user.getId())
                .orElseThrow(() -> new BusinessException("RESUME_005", "Resume not found"));

        resumeStorageService.delete(resume.getFileUrl());
        resume.setDeletedAt(LocalDateTime.now());
        resumeRepository.save(resume);
    }

    /**
     * Parse sort string in the form of "property,asc|desc".
     *
     * @param sortStr sort query string
     * @return Spring Data {@link Sort}
     */
    private Sort parseSort(String sortStr) {
        if (sortStr == null || sortStr.isEmpty()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String[] parts = sortStr.split(",");
        String property = parts[0];
        Sort.Direction direction = parts.length > 1 && parts[1].equalsIgnoreCase("asc") ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, property);
    }

    /**
     * Map resume entity to list response DTO.
     *
     * @param resume resume entity
     * @return response DTO
     */
    private ResumeResponseDTO mapToResponseDTO(Resume resume) {
        return ResumeResponseDTO.builder()
                .id(resume.getExternalId())
                .fileName(resume.getFileName())
                .fileType(resume.getFileType())
                .fileSize(resume.getFileSize())
                .targetJob(resume.getTargetJob())
                .status(resume.getStatus())
                .analysisStatus(resume.getAnalysisStatus())
                .hasAnalysis(resume.getAnalysisStatus() == AnalysisStatus.COMPLETED)
                .createdAt(resume.getCreatedAt())
                .updatedAt(resume.getUpdatedAt())
                .build();
    }

    /**
     * Map resume entity to upload response DTO.
     *
     * @param resume resume entity
     * @return upload response DTO
     */
    private ResumeUploadResponseDTO mapToUploadResponseDTO(Resume resume) {
        return ResumeUploadResponseDTO.builder()
                .id(resume.getExternalId())
                .fileName(resume.getFileName())
                .fileType(resume.getFileType())
                .fileSize(resume.getFileSize())
                .targetJob(resume.getTargetJob())
                .status(resume.getStatus())
                .createdAt(resume.getCreatedAt())
                .build();
    }

    /**
     * Parse and normalize status filter.
     *
     * @param query resume query
     * @return normalized {@link ResumeStatus} or null
     */
    private ResumeStatus parseAndNormalizeStatusFilter(ResumeQueryDTO query) {
        if (query == null || query.getStatus() == null) {
            return null;
        }

        String raw = query.getStatus();
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);

        try {
            ResumeStatus status = ResumeStatus.valueOf(normalized);
            query.setStatus(normalized);
            return status;
        } catch (IllegalArgumentException e) {
            throw new BusinessException("RESUME_008", "Invalid status value: " + raw);
        }
    }

    /**
     * Validate an uploaded file by extension, content type and size.
     *
     * @param file uploaded file
     * @return normalized file extension (lowercase)
     */
    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("RESUME_001", "Unsupported file format");
        }

        String originalName = file.getOriginalFilename();
        String extension = getExtension(originalName);
        SupportedFileType supportedFileType = SUPPORTED_FILE_TYPES.get(extension);
        if (supportedFileType == null) {
            throw new BusinessException("RESUME_001", "Unsupported file format");
        }

        String contentType = normalizeContentType(file.getContentType());
        if (contentType != null && !supportedFileType.mimeTypes().contains(contentType)) {
            throw new BusinessException("RESUME_001", "Unsupported file format");
        }

        if (file.getSize() > supportedFileType.maxSize()) {
            throw new BusinessException("RESUME_002", "File size exceeds the limit");
        }
        return extension;
    }

    /**
     * Extract lower-case file extension.
     *
     * @param fileName original file name
     * @return extension without dot, or empty string
     */
    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Normalize file name for persistence.
     *
     * @param fileName original file name
     * @return normalized file name
     */
    private String normalizeFileName(String fileName) {
        return (fileName == null || fileName.isBlank()) ? "resume" : fileName;
    }

    /**
     * Resolve file type (MIME) for persistence.
     *
     * @param file uploaded file
     * @param extension normalized extension
     * @return mime type
     */
    private String resolveFileType(MultipartFile file, String extension) {
        String contentType = normalizeContentType(file.getContentType());
        if (contentType != null) {
            return contentType;
        }
        return SUPPORTED_FILE_TYPES.get(extension).defaultMimeType();
    }

    /**
     * Normalize a MIME content type.
     *
     * @param contentType raw content type
     * @return normalized content type or null
     */
    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        return contentType.toLowerCase(Locale.ROOT);
    }

    /**
     * Normalize target job field.
     *
     * @param targetJob raw target job
     * @return trimmed target job or null
     */
    private String normalizeTargetJob(String targetJob) {
        if (targetJob == null) {
            return null;
        }
        String trimmed = targetJob.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Calculate sha-256 hash for the uploaded file bytes.
     *
     * @param file uploaded file
     * @return lowercase hex digest
     */
    private String calculateSha256(MultipartFile file) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = messageDigest.digest(file.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte hashByte : hashBytes) {
                hexString.append(String.format("%02x", hashByte));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException | java.io.IOException ex) {
            throw new BusinessException("FILE_001", "File upload failed", ex);
        }
    }

    /**
     * Supported file type definition.
     *
     * @param mimeTypes allowed MIME types
     * @param maxSize max size in bytes
     * @param defaultMimeType fallback MIME type when request content type is absent
     */
    private record SupportedFileType(Set<String> mimeTypes, long maxSize, String defaultMimeType) {
    }
}
