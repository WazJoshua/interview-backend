package com.josh.interviewj.knowledgebase.model;

/**
 * Enumerates the lifecycle states for deferred knowledge base file cleanup work.
 */
public enum KbFileCleanupTaskStatus {
    PENDING,
    RETRY,
    COMPLETED,
    FAILED
}
