package com.josh.interviewj.llm.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for purpose-specific circuit breakers.
 *
 * <p>Creates and caches circuit breaker instances per purpose.
 * Configuration is driven by application properties with defaults.</p>
 */
@Component
public class LlmPurposeCircuitBreakerRegistry {

    private final ConcurrentMap<String, LlmPurposeCircuitBreaker> breakers = new ConcurrentHashMap<>();

    private final int defaultFailureThreshold;
    private final Duration defaultOpenDuration;
    private final Duration defaultWindowDuration;

    public LlmPurposeCircuitBreakerRegistry(
            @Value("${app.llm.breaker.default-failure-threshold:3}") int defaultFailureThreshold,
            @Value("${app.llm.breaker.default-open-duration-seconds:30}") int defaultOpenDurationSeconds,
            @Value("${app.llm.breaker.default-window-duration-seconds:60}") int defaultWindowDurationSeconds
    ) {
        this.defaultFailureThreshold = Math.max(1, defaultFailureThreshold);
        this.defaultOpenDuration = Duration.ofSeconds(Math.max(1, defaultOpenDurationSeconds));
        this.defaultWindowDuration = Duration.ofSeconds(Math.max(1, defaultWindowDurationSeconds));
    }

    /**
     * Get or create a circuit breaker for the given purpose.
     *
     * @param purpose LLM purpose key
     * @return circuit breaker instance
     */
    public LlmPurposeCircuitBreaker getBreaker(String purpose) {
        return breakers.computeIfAbsent(purpose, this::createBreaker);
    }

    private LlmPurposeCircuitBreaker createBreaker(String purpose) {
        return new LlmPurposeCircuitBreaker(
                purpose,
                defaultFailureThreshold,
                defaultOpenDuration,
                defaultWindowDuration
        );
    }

    /**
     * Reset all circuit breakers (primarily for testing).
     */
    public void resetAll() {
        breakers.clear();
    }

    /**
     * Get the number of registered circuit breakers.
     *
     * @return count
     */
    public int size() {
        return breakers.size();
    }
}
