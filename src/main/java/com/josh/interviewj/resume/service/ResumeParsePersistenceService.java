package com.josh.interviewj.resume.service;

import com.josh.interviewj.resume.model.Resume;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class ResumeParsePersistenceService {

    private final ResumeRepository resumeRepository;
    private final ResumeStorageService resumeStorageService;
    private final DocumentParserService documentParserService;

    /**
     * Extract raw text from the resume file and persist it to the resume record.
     *
     * @param resumeId resume primary key
     * @return extracted raw text
     */
    @Transactional
    public String extractAndSaveRawText(Long resumeId) {
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException("RESUME_005", "Resume not found"));

        // Resolve stored file path and extract text.
        Path filePath = resumeStorageService.getFilePath(resume.getFileUrl());
        String rawText = documentParserService.extractText(filePath, resume.getFileType());

        resume.setRawText(rawText);
        resumeRepository.save(resume);
        return rawText;
    }

    /**
     * Persist structured parsed content to the resume record.
     *
     * @param resumeId resume primary key
     * @param parsedContent parsed content JSON string
     */
    @Transactional
    public void saveParsedContent(Long resumeId, String parsedContent) {
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException("RESUME_005", "Resume not found"));

        resume.setParsedContent(parsedContent);
        resumeRepository.save(resume);
    }
}
