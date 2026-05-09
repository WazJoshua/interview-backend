package com.josh.interviewj.llm.gateway.executor;

import com.josh.interviewj.llm.gateway.dto.AiInvocationKind;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class AiCapabilityExecutorRegistry {

    private final Map<AiInvocationKind, AiCapabilityExecutor> executors;

    public AiCapabilityExecutorRegistry(List<AiCapabilityExecutor> executors) {
        EnumMap<AiInvocationKind, AiCapabilityExecutor> mapping = new EnumMap<>(AiInvocationKind.class);
        for (AiCapabilityExecutor executor : executors) {
            mapping.put(executor.supportsKind(), executor);
        }
        this.executors = Map.copyOf(mapping);
    }

    public AiCapabilityExecutor get(AiInvocationKind kind) {
        AiCapabilityExecutor executor = executors.get(kind);
        if (executor == null) {
            throw new IllegalArgumentException("Unsupported AI invocation kind: " + kind);
        }
        return executor;
    }
}
