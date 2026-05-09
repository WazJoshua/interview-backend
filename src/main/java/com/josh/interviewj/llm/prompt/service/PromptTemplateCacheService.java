package com.josh.interviewj.llm.prompt.service;

import com.josh.interviewj.llm.prompt.dto.PromptTemplateSnapshot;
import com.josh.interviewj.llm.prompt.support.PromptTemplateSnapshotLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache service for prompt template snapshots.
 * Uses lazy loading and supports invalidation.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PromptTemplateCacheService {

    private final PromptTemplateSnapshotLoader snapshotLoader;
    private final ConcurrentHashMap<String, PromptTemplateSnapshot> cache = new ConcurrentHashMap<>();

    /**
     * Get cached snapshot for template key.
     * Loads from database if not cached.
     */
    public PromptTemplateSnapshot get(String templateKey) {
        return cache.computeIfAbsent(templateKey, key -> {
            log.debug("Loading template snapshot for: {}", key);
            return snapshotLoader.load(key).orElse(null);
        });
    }

    /**
     * Invalidate all cached templates.
     */
    public void invalidateAll() {
        log.info("Invalidating all cached prompt templates");
        cache.clear();
    }

    /**
     * Invalidate a specific template.
     */
    public void invalidate(String templateKey) {
        log.debug("Invalidating cached template: {}", templateKey);
        cache.remove(templateKey);
    }

    /**
     * Get cache size for monitoring.
     */
    public int size() {
        return cache.size();
    }
}