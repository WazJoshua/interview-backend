package com.josh.interviewj.usage.service;

import com.josh.interviewj.admin.model.AdminOperationActionType;
import com.josh.interviewj.admin.model.AdminOperationLog;
import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.repository.AdminOperationLogRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.llm.prompt.model.LlmPromptTemplate;
import com.josh.interviewj.llm.prompt.model.LlmPromptTemplateRevision;
import com.josh.interviewj.llm.prompt.repository.LlmPromptTemplateRepository;
import com.josh.interviewj.llm.prompt.repository.LlmPromptTemplateRevisionRepository;
import com.josh.interviewj.llm.prompt.service.PromptTemplateRenderer;
import com.josh.interviewj.llm.prompt.service.PromptTemplateValidationService;
import com.josh.interviewj.llm.support.LlmConfigChangeService;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplateCreateRequest;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplateListQuery;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplatePreviewRequest;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplateRevisionCreateRequest;
import com.josh.interviewj.usage.dto.request.AdminPromptTemplateToggleRequest;
import com.josh.interviewj.usage.dto.response.AdminPromptTemplateDetailResponse;
import com.josh.interviewj.usage.dto.response.AdminPromptTemplateListItemResponse;
import com.josh.interviewj.usage.dto.response.AdminPromptTemplatePreviewResponse;
import com.josh.interviewj.usage.dto.response.AdminPromptTemplateRevisionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Admin service for managing prompt templates.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminPromptTemplateService {

    private final LlmPromptTemplateRepository templateRepository;
    private final LlmPromptTemplateRevisionRepository revisionRepository;
    private final LlmConfigChangeService llmConfigChangeService;
    private final AdminOperationLogRepository adminOperationLogRepository;
    private final PromptTemplateValidationService validationService;
    private final PromptTemplateRenderer renderer;

    /**
     * List templates with optional filtering.
     */
    public Page<AdminPromptTemplateListItemResponse> listTemplates(AdminPromptTemplateListQuery query, Pageable pageable) {
        List<LlmPromptTemplate> templates;

        if (query.domain() != null && query.purpose() != null) {
            templates = templateRepository.findByDomainAndPurpose(query.domain(), query.purpose());
        } else if (query.domain() != null) {
            templates = templateRepository.findByDomain(query.domain());
        } else if (query.enabled() != null && query.enabled()) {
            templates = templateRepository.findByEnabledTrue();
        } else {
            templates = templateRepository.findAll();
        }

        // Apply enabled filter if specified
        if (query.enabled() != null) {
            templates = templates.stream()
                    .filter(t -> t.getEnabled().equals(query.enabled()))
                    .collect(Collectors.toList());
        }

        // Apply pagination slicing with boundary protection
        int total = templates.size();
        int start = (int) pageable.getOffset();
        if (start >= total) {
            // Requested page is beyond available data, return empty page
            return new PageImpl<>(List.of(), pageable, total);
        }
        int end = Math.min(start + pageable.getPageSize(), total);

        List<AdminPromptTemplateListItemResponse> items = templates.subList(start, end).stream()
                .map(this::toListItemResponse)
                .collect(Collectors.toList());

        return new PageImpl<>(items, pageable, total);
    }

    /**
     * Get template detail.
     */
    public AdminPromptTemplateDetailResponse getTemplate(String templateKey) {
        LlmPromptTemplate template = getTemplateOrThrow(templateKey);

        List<LlmPromptTemplateRevision> revisions = revisionRepository.findByTemplateIdOrderByRevisionNoDesc(template.getId());

        Optional<LlmPromptTemplateRevision> activeRevision = findActiveRevision(template);

        List<AdminPromptTemplateDetailResponse.RevisionSummary> revisionSummaries = revisions.stream()
                .map(r -> new AdminPromptTemplateDetailResponse.RevisionSummary(
                        r.getRevisionNo(), r.getChangeNote(), r.getCreatedAt(), r.getCreatedBy()
                ))
                .collect(Collectors.toList());

        return new AdminPromptTemplateDetailResponse(
                template.getTemplateKey(),
                template.getDomain(),
                template.getPurpose(),
                template.getInvocationKind(),
                template.getDescription(),
                template.getEnabled(),
                activeRevision.map(LlmPromptTemplateRevision::getRevisionNo).orElse(null),
                activeRevision.map(LlmPromptTemplateRevision::getSystemTemplate).orElse(null),
                activeRevision.map(LlmPromptTemplateRevision::getUserTemplate).orElse(null),
                activeRevision.map(LlmPromptTemplateRevision::getVariables).orElse(null),
                template.getCreatedAt(),
                template.getUpdatedAt(),
                template.getCreatedBy(),
                template.getUpdatedBy(),
                revisionSummaries
        );
    }

    /**
     * Create template with initial revision.
     */
    @Transactional
    public LlmPromptTemplate createTemplate(Long actorUserId, AdminPromptTemplateCreateRequest request) {
        // Check duplicate
        if (templateRepository.existsByTemplateKey(request.templateKey())) {
            throw new BusinessException(ErrorCode.ADMIN_LLM_002, "Template key already exists: " + request.templateKey());
        }

        String variablesJson = toJson(request.variables());
        validateTemplateOrThrow(request.systemTemplate(), request.userTemplate(), variablesJson);
        requireAnyTemplate(request.systemTemplate(), request.userTemplate());

        LocalDateTime now = LocalDateTime.now();
        String actor = String.valueOf(actorUserId);

        // Create template identity - audit fields always from authenticated user
        LlmPromptTemplate template = LlmPromptTemplate.builder()
                .templateKey(request.templateKey())
                .domain(request.domain())
                .purpose(request.purpose())
                .invocationKind(request.invocationKind() != null ? request.invocationKind() : "CHAT")
                .description(request.description())
                .enabled(request.enabled() != null ? request.enabled() : true)
                .createdBy(actor)
                .updatedBy(actor)
                .createdAt(now)
                .updatedAt(now)
                .build();
        template = templateRepository.save(template);

        LlmPromptTemplateRevision revision = buildRevision(
                template.getId(),
                1,
                request.systemTemplate(),
                request.userTemplate(),
                variablesJson,
                request.changeNote() != null ? request.changeNote() : "Initial revision",
                actor,
                now
        );
        revision = revisionRepository.save(revision);

        // Set active revision
        template.setActiveRevisionId(revision.getId());
        templateRepository.save(template);

        // Record change and log
        llmConfigChangeService.recordChange("PROMPT_TEMPLATE_CREATED", request.templateKey());
        writeAdminLog(actorUserId, "CREATE", request.templateKey());

        return template;
    }

    /**
     * Create new revision.
     */
    @Transactional
    public LlmPromptTemplateRevision createRevision(Long actorUserId, String templateKey, AdminPromptTemplateRevisionCreateRequest request) {
        LlmPromptTemplate template = getTemplateOrThrow(templateKey);

        String variablesJson = toJson(request.variables());
        validateTemplateOrThrow(request.systemTemplate(), request.userTemplate(), variablesJson);
        requireAnyTemplate(request.systemTemplate(), request.userTemplate());

        // Get max revision number
        Integer maxRevision = revisionRepository.findMaxRevisionNo(template.getId()).orElse(0);
        int nextRevisionNo = maxRevision + 1;

        LocalDateTime now = LocalDateTime.now();
        String actor = String.valueOf(actorUserId);

        LlmPromptTemplateRevision revision = buildRevision(
                template.getId(),
                nextRevisionNo,
                request.systemTemplate(),
                request.userTemplate(),
                variablesJson,
                request.changeNote() != null ? request.changeNote() : "Revision " + nextRevisionNo,
                actor,
                now
        );
        revision = revisionRepository.save(revision);

        // Record change and log
        llmConfigChangeService.recordChange("PROMPT_TEMPLATE_REVISION_CREATED", templateKey + ":" + nextRevisionNo);
        writeAdminLog(actorUserId, "CREATE_REVISION", templateKey + ":" + nextRevisionNo);

        return revision;
    }

    /**
     * Publish a revision as active.
     */
    @Transactional
    public void publishRevision(Long actorUserId, String templateKey, Integer revisionNo) {
        LlmPromptTemplate template = getTemplateOrThrow(templateKey);

        if (!template.getEnabled()) {
            throw new BusinessException(ErrorCode.ADMIN_LLM_001, "Cannot publish revision for disabled template");
        }

        // Find revision by template scope
        LlmPromptTemplateRevision revision = revisionRepository.findByTemplateIdAndRevisionNo(template.getId(), revisionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_LLM_001,
                        "Revision not found: " + templateKey + ":" + revisionNo));

        // Update active revision
        template.setActiveRevisionId(revision.getId());
        template.setUpdatedAt(LocalDateTime.now());
        template.setUpdatedBy(String.valueOf(actorUserId));
        templateRepository.save(template);

        // Record change and log
        llmConfigChangeService.recordChange("PROMPT_TEMPLATE_PUBLISHED", templateKey + ":" + revisionNo);
        writeAdminLog(actorUserId, "PUBLISH", templateKey + ":" + revisionNo);
    }

    /**
     * Toggle template enabled status.
     */
    @Transactional
    public void toggleTemplate(Long actorUserId, String templateKey, AdminPromptTemplateToggleRequest request) {
        LlmPromptTemplate template = getTemplateOrThrow(templateKey);

        template.setEnabled(request.enabled());
        template.setUpdatedAt(LocalDateTime.now());
        // Audit field always from authenticated user, never from request
        template.setUpdatedBy(String.valueOf(actorUserId));
        templateRepository.save(template);

        // Record change and log
        llmConfigChangeService.recordChange("PROMPT_TEMPLATE_TOGGLE", templateKey + ":" + request.enabled());
        writeAdminLog(actorUserId, "TOGGLE", templateKey + ":" + request.enabled());
    }

    /**
     * Preview draft template content.
     */
    public AdminPromptTemplatePreviewResponse previewDraft(AdminPromptTemplatePreviewRequest request) {
        String variablesJson = toJson(request.variables());
        String validationError = validateTemplate(request.systemTemplate(), request.userTemplate(), variablesJson);
        if (validationError != null) {
            return previewFailure(validationError);
        }

        String requiredVariableError = validateRequiredRenderVariables(request.variables(), request.renderVariables());
        if (requiredVariableError != null) {
            return previewFailure(requiredVariableError);
        }

        try {
            Map<String, Object> safeRenderVariables = request.renderVariables() != null ? request.renderVariables() : Map.of();
            String renderedSystem = renderer.render(request.systemTemplate(), safeRenderVariables);
            String renderedUser = renderer.render(request.userTemplate(), safeRenderVariables);
            return new AdminPromptTemplatePreviewResponse(renderedSystem, renderedUser, true, null);
        } catch (IllegalArgumentException e) {
            return previewFailure(e.getMessage());
        }
    }

    /**
     * Validate that required variables have values in renderVariables.
     */
    private String validateRequiredRenderVariables(
            List<?> variableDeclarations,
            Map<String, Object> renderVariables
    ) {
        if (variableDeclarations == null || variableDeclarations.isEmpty()) {
            return null;
        }

        for (Object declaration : variableDeclarations) {
            RequiredVariable requiredVariable = toRequiredVariable(declaration);
            if (requiredVariable == null || !requiredVariable.required()) {
                continue;
            }
            if (renderVariables == null
                    || !renderVariables.containsKey(requiredVariable.name())
                    || renderVariables.get(requiredVariable.name()) == null) {
                return "Required variable missing sample value: " + requiredVariable.name();
            }
        }

        return null;
    }

    /**
     * Get revisions for a template.
     */
    public List<AdminPromptTemplateRevisionResponse> getRevisions(String templateKey) {
        LlmPromptTemplate template = getTemplateOrThrow(templateKey);

        List<LlmPromptTemplateRevision> revisions = revisionRepository.findByTemplateIdOrderByRevisionNoDesc(template.getId());
        return revisions.stream()
                .map(this::toRevisionResponse)
                .collect(Collectors.toList());
    }

    private AdminPromptTemplateListItemResponse toListItemResponse(LlmPromptTemplate template) {
        Integer activeRevisionNo = findActiveRevision(template)
                .map(LlmPromptTemplateRevision::getRevisionNo)
                .orElse(null);
        return new AdminPromptTemplateListItemResponse(
                template.getTemplateKey(),
                template.getDomain(),
                template.getPurpose(),
                template.getInvocationKind(),
                template.getDescription(),
                template.getEnabled(),
                activeRevisionNo,
                template.getCreatedAt(),
                template.getUpdatedAt(),
                template.getCreatedBy(),
                template.getUpdatedBy()
        );
    }

    private AdminPromptTemplateRevisionResponse toRevisionResponse(LlmPromptTemplateRevision revision) {
        return new AdminPromptTemplateRevisionResponse(
                revision.getId(),
                revision.getRevisionNo(),
                revision.getSystemTemplate(),
                revision.getUserTemplate(),
                revision.getVariables(),
                revision.getChangeNote(),
                revision.getCreatedAt(),
                revision.getCreatedBy()
        );
    }

    private String validateTemplate(String systemTemplate, String userTemplate, String variablesJson) {
        return validationService.validateWriteTemplate(systemTemplate, userTemplate, variablesJson);
    }

    private void validateTemplateOrThrow(String systemTemplate, String userTemplate, String variablesJson) {
        String validationError = validateTemplate(systemTemplate, userTemplate, variablesJson);
        if (validationError != null) {
            throw new BusinessException(ErrorCode.ADMIN_LLM_001, validationError);
        }
    }

    private void requireAnyTemplate(String systemTemplate, String userTemplate) {
        if (systemTemplate == null && userTemplate == null) {
            throw new BusinessException(ErrorCode.ADMIN_LLM_001, "At least system or user template must be provided");
        }
    }

    private LlmPromptTemplate getTemplateOrThrow(String templateKey) {
        return templateRepository.findByTemplateKey(templateKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_LLM_001, "Template not found: " + templateKey));
    }

    private Optional<LlmPromptTemplateRevision> findActiveRevision(LlmPromptTemplate template) {
        if (template.getActiveRevisionId() == null) {
            return Optional.empty();
        }
        return revisionRepository.findById(template.getActiveRevisionId());
    }

    private LlmPromptTemplateRevision buildRevision(
            Long templateId,
            int revisionNo,
            String systemTemplate,
            String userTemplate,
            String variablesJson,
            String changeNote,
            String createdBy,
            LocalDateTime createdAt
    ) {
        return LlmPromptTemplateRevision.builder()
                .templateId(templateId)
                .revisionNo(revisionNo)
                .systemTemplate(systemTemplate)
                .userTemplate(userTemplate)
                .variables(variablesJson)
                .changeNote(changeNote)
                .createdBy(createdBy)
                .createdAt(createdAt)
                .build();
    }

    private AdminPromptTemplatePreviewResponse previewFailure(String errorMessage) {
        return new AdminPromptTemplatePreviewResponse(null, null, false, errorMessage);
    }

    private RequiredVariable toRequiredVariable(Object declaration) {
        if (declaration instanceof AdminPromptTemplateCreateRequest.VariableDeclaration variableDeclaration) {
            return new RequiredVariable(variableDeclaration.name(), variableDeclaration.required());
        }
        if (declaration instanceof AdminPromptTemplateRevisionCreateRequest.VariableDeclaration variableDeclaration) {
            return new RequiredVariable(variableDeclaration.name(), variableDeclaration.required());
        }
        if (declaration instanceof AdminPromptTemplatePreviewRequest.VariableDeclaration variableDeclaration) {
            return new RequiredVariable(variableDeclaration.name(), variableDeclaration.required());
        }
        return null;
    }

    private void writeAdminLog(Long actorUserId, String action, String resourceId) {
        AdminOperationLog log = AdminOperationLog.builder()
                .actorUserId(actorUserId)
                .resourceType(AdminOperationResourceType.LLM_PROMPT_TEMPLATE)
                .actionType(AdminOperationActionType.valueOf(action))
                .resourceId(resourceId)
                .createdAt(LocalDateTime.now())
                .build();
        adminOperationLogRepository.save(log);
    }

    private String toJson(List<?> variables) {
        if (variables == null || variables.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < variables.size(); i++) {
            if (i > 0) sb.append(",");
            Object v = variables.get(i);
            if (v instanceof AdminPromptTemplateCreateRequest.VariableDeclaration) {
                AdminPromptTemplateCreateRequest.VariableDeclaration vd = (AdminPromptTemplateCreateRequest.VariableDeclaration) v;
                sb.append("{\"name\":\"").append(vd.name()).append("\",\"required\":").append(vd.required()).append("}");
            } else if (v instanceof AdminPromptTemplateRevisionCreateRequest.VariableDeclaration) {
                AdminPromptTemplateRevisionCreateRequest.VariableDeclaration vd = (AdminPromptTemplateRevisionCreateRequest.VariableDeclaration) v;
                sb.append("{\"name\":\"").append(vd.name()).append("\",\"required\":").append(vd.required()).append("}");
            } else if (v instanceof AdminPromptTemplatePreviewRequest.VariableDeclaration) {
                AdminPromptTemplatePreviewRequest.VariableDeclaration vd = (AdminPromptTemplatePreviewRequest.VariableDeclaration) v;
                sb.append("{\"name\":\"").append(vd.name()).append("\",\"required\":").append(vd.required()).append("}");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private record RequiredVariable(String name, boolean required) {
    }
}
