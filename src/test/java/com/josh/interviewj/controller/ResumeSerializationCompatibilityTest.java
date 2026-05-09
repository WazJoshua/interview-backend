package com.josh.interviewj.controller;

import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.resume.dto.response.ResumeDetailResponseDTO;
import com.josh.interviewj.resume.model.AnalysisStatus;
import com.josh.interviewj.resume.model.ResumeStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeSerializationCompatibilityTest {

    private static final ObjectMapper RUNTIME_MAPPER = JsonMapper.builder().build();

    /**
     * Resume detail payload should serialize parsedContent as business JSON fields.
     *
     * @throws Exception when test serialization fails
     */
    @Test
    void parsedContent_SerializesAsBusinessJson_NotBeanProperties() throws Exception {
        ResumeDetailResponseDTO detail = ResumeDetailResponseDTO.builder()
                .id(UUID.fromString("7fd8e0c6-2d15-4677-90f3-0f0de0b0ea71"))
                .fileName("resume.pdf")
                .fileType("application/pdf")
                .fileSize(2048L)
                .targetJob("Java Developer")
                .status(ResumeStatus.PARSED)
                .analysisStatus(AnalysisStatus.PENDING)
                .hasAnalysis(false)
                .parsedContent(RUNTIME_MAPPER.readTree("""
                        {"name":"Josh","skills":["Java"]}
                        """))
                .createdAt(LocalDateTime.of(2026, 3, 12, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 3, 12, 10, 5))
                .build();

        ApiResponse<ResumeDetailResponseDTO> response = ApiResponse.<ResumeDetailResponseDTO>builder()
                .code(200)
                .message("success")
                .data(detail)
                .build();

        String serialized = RUNTIME_MAPPER.writeValueAsString(response);

        assertThat(serialized).contains("\"parsedContent\":");
        assertThat(serialized).contains("\"name\":\"Josh\"");
        assertThat(serialized).contains("\"skills\":[\"Java\"]");
        assertThat(serialized).doesNotContain("\"containerNode\"");
    }
}
