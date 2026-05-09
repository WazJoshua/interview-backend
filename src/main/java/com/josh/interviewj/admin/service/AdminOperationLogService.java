package com.josh.interviewj.admin.service;

import com.josh.interviewj.admin.model.AdminOperationActionType;
import com.josh.interviewj.admin.model.AdminOperationLog;
import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.repository.AdminOperationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AdminOperationLogService {

    private final ObjectMapper objectMapper;
    private final AdminOperationLogRepository adminOperationLogRepository;

    public AdminOperationLog recordCreate(
            Long actorUserId,
            AdminOperationResourceType resourceType,
            String resourceId,
            String requestId,
            Object afterSnapshot,
            Object metadata
    ) {
        return record(
                actorUserId,
                AdminOperationActionType.CREATE,
                resourceType,
                resourceId,
                requestId,
                null,
                afterSnapshot,
                metadata
        );
    }

    public AdminOperationLog recordUpdate(
            Long actorUserId,
            AdminOperationResourceType resourceType,
            String resourceId,
            String requestId,
            Object beforeSnapshot,
            Object afterSnapshot,
            Object metadata
    ) {
        return record(
                actorUserId,
                AdminOperationActionType.UPDATE,
                resourceType,
                resourceId,
                requestId,
                beforeSnapshot,
                afterSnapshot,
                metadata
        );
    }

    public AdminOperationLog record(
            Long actorUserId,
            AdminOperationActionType actionType,
            AdminOperationResourceType resourceType,
            String resourceId,
            String requestId,
            Object beforeSnapshot,
            Object afterSnapshot,
            Object metadata
    ) {
        AdminOperationLog operationLog = AdminOperationLog.builder()
                .actorUserId(actorUserId)
                .actionType(actionType)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .requestId(requestId)
                .beforeSnapshot(serialize(beforeSnapshot))
                .afterSnapshot(serialize(afterSnapshot))
                .metadata(serialize(metadata))
                .build();
        return adminOperationLogRepository.save(operationLog);
    }

    private String serialize(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize admin operation log snapshot", exception);
        }
    }
}
