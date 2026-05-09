package com.josh.interviewj.llm.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory circuit breaker for LLM purpose-specific fault tolerance.
 *
 * <p>Tracks consecutive failures and opens the circuit when threshold is exceeded.
 * After open duration, transitions to half-open state allowing a single test request.</p>
 */
public class LlmPurposeCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(LlmPurposeCircuitBreaker.class);

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final String purpose;
    private final int failureThreshold;
    private final Duration openDuration;
    private final Duration windowDuration;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger windowFailureCount = new AtomicInteger(0);
    private final AtomicReference<Instant> windowStart = new AtomicReference<>(Instant.now());
    private final AtomicReference<Instant> openedAt = new AtomicReference<>(null);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicBoolean halfOpenProbeSent = new AtomicBoolean(false);
    private final ReentrantLock stateLock = new ReentrantLock();

    public LlmPurposeCircuitBreaker(String purpose, int failureThreshold, Duration openDuration, Duration windowDuration) {
        this.purpose = purpose;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration != null ? openDuration : Duration.ofSeconds(30);
        this.windowDuration = windowDuration != null ? windowDuration : Duration.ofMinutes(1);
    }

    /**
     * Check if a request is allowed through the circuit breaker.
     *
     * @return true if allowed, false if circuit is open
     */
    public boolean allow() {
        while (true) {
            State currentState = state.get();

            if (currentState == State.CLOSED) {
                return true;
            }

            if (currentState == State.OPEN) {
                Instant openedTime = openedAt.get();
                if (openedTime == null) {
                    // Invalid state, try to transition
                    return tryTransitionToHalfOpenAndAcquireProbe();
                }

                if (!Instant.now().isBefore(openedTime.plus(openDuration))) {
                    // Open duration expired, try to transition
                    return tryTransitionToHalfOpenAndAcquireProbe();
                }

                log.debug("Circuit breaker OPEN for purpose={}, remaining={}ms",
                        purpose, openDuration.minus(Duration.between(openedTime, Instant.now())).toMillis());
                return false;
            }

            // HALF_OPEN: allow only one test request
            // Use CAS to ensure only one probe is sent
            return halfOpenProbeSent.compareAndSet(false, true);
        }
    }

    /**
     * Try to transition from OPEN to HALF_OPEN and acquire the probe permission.
     * Only returns true if this thread successfully transitioned AND acquired the probe.
     *
     * @return true if this thread acquired the probe permission
     */
    private boolean tryTransitionToHalfOpenAndAcquireProbe() {
        stateLock.lock();
        try {
            if (state.get() == State.OPEN) {
                state.set(State.HALF_OPEN);
                halfOpenProbeSent.set(false);  // Reset probe flag when entering HALF_OPEN
                log.info("Circuit breaker transitioned to HALF_OPEN for purpose={}", purpose);
                // Now acquire the probe permission via CAS
                return halfOpenProbeSent.compareAndSet(false, true);
            }
            // Another thread already transitioned to HALF_OPEN
            // Return false so caller will re-read state and handle HALF_OPEN via CAS
            return false;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Record a successful request.
     */
    public void recordSuccess() {
        State previousState = state.get();

        if (previousState == State.HALF_OPEN) {
            stateLock.lock();
            try {
                if (state.get() == State.HALF_OPEN) {
                    reset();
                    log.info("Circuit breaker CLOSED for purpose={} after successful test request", purpose);
                }
            } finally {
                stateLock.unlock();
            }
        }

        consecutiveFailures.set(0);
    }

    /**
     * Record a failed request.
     */
    public void recordFailure() {
        incrementWindowFailureCount();
        int failures = consecutiveFailures.incrementAndGet();

        State currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            stateLock.lock();
            try {
                if (state.get() == State.HALF_OPEN) {
                    trip();
                    log.warn("Circuit breaker OPEN for purpose={} after failed test request", purpose);
                }
            } finally {
                stateLock.unlock();
            }
            return;
        }

        if (currentState == State.CLOSED && failures >= failureThreshold) {
            stateLock.lock();
            try {
                if (state.get() == State.CLOSED && consecutiveFailures.get() >= failureThreshold) {
                    trip();
                    log.warn("Circuit breaker OPEN for purpose={} after {} consecutive failures",
                            purpose, consecutiveFailures.get());
                }
            } finally {
                stateLock.unlock();
            }
        }
    }

    private void trip() {
        state.set(State.OPEN);
        openedAt.set(Instant.now());
    }

    private void reset() {
        state.set(State.CLOSED);
        openedAt.set(null);
        consecutiveFailures.set(0);
    }

    private void incrementWindowFailureCount() {
        Instant now = Instant.now();
        Instant windowStart = this.windowStart.get();

        if (windowStart != null && now.isAfter(windowStart.plus(windowDuration))) {
            this.windowStart.compareAndSet(windowStart, now);
            windowFailureCount.set(1);
        } else {
            windowFailureCount.incrementAndGet();
        }
    }

    /**
     * Get current circuit breaker state.
     *
     * @return current state
     */
    public State getState() {
        return state.get();
    }

    /**
     * Get current consecutive failure count.
     *
     * @return consecutive failures
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * Get purpose name.
     *
     * @return purpose
     */
    public String getPurpose() {
        return purpose;
    }
}
