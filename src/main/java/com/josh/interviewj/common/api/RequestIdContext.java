package com.josh.interviewj.common.api;

import java.util.UUID;

/**
 * Thread-local request id context shared by response and exception rendering.
 */
public final class RequestIdContext {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String ALTERNATE_HEADER_NAME = "X-Request-ID";
    public static final String ATTRIBUTE_NAME = "requestId";

    private static final ThreadLocal<String> REQUEST_ID_HOLDER = new ThreadLocal<>();

    private RequestIdContext() {
    }

    public static void set(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        REQUEST_ID_HOLDER.set(requestId);
    }

    public static String get() {
        return REQUEST_ID_HOLDER.get();
    }

    public static String getOrCreate() {
        String existing = REQUEST_ID_HOLDER.get();
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = generate();
        REQUEST_ID_HOLDER.set(generated);
        return generated;
    }

    public static void clear() {
        REQUEST_ID_HOLDER.remove();
    }

    public static String normalizeIncoming(String requestId) {
        if (requestId == null) {
            return null;
        }
        String trimmed = requestId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String generate() {
        return "req_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
