package com.josh.interviewj.resume.dto.response;

import com.josh.interviewj.resume.model.ResumeStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response returned after resume upload request is accepted.
 */
@Data
@Builder
public class ResumeUploadResponseDTO {

    private UUID id;

    private String fileName;

    private String fileType;

    private Long fileSize;

    private String targetJob;

    private ResumeStatus status;

    private LocalDateTime createdAt;
}
