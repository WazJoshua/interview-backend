package com.josh.interviewj.resume.dto.response;

import com.josh.interviewj.resume.model.ResumeStatus;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class ResumeParseResponseDTO {

    private UUID id;

    private ResumeStatus status;
}

