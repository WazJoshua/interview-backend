package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.knowledgebase.model.KbDocumentArtifact;
import com.josh.interviewj.knowledgebase.model.KbDocumentArtifactType;
import com.josh.interviewj.knowledgebase.repository.KbDocumentArtifactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class KbDocumentArtifactService {

    private final KbDocumentArtifactRepository kbDocumentArtifactRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertNormalizedReviewText(Long documentId, String content, Map<String, Object> metadata) {
        kbDocumentArtifactRepository.upsertArtifact(
                documentId,
                KbDocumentArtifactType.NORMALIZED_REVIEW_TEXT.name(),
                content == null ? "" : content,
                serializeMetadata(metadata)
        );
    }

    public Optional<KbDocumentArtifact> findArtifact(Long documentId, KbDocumentArtifactType artifactType) {
        return kbDocumentArtifactRepository.findByDocumentIdAndArtifactType(documentId, artifactType);
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception ex) {
            throw new RuntimeException("文档 artifact 序列化失败", ex);
        }
    }
}
