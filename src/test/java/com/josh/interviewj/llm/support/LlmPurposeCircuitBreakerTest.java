package com.josh.interviewj.llm.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPurposeCircuitBreakerTest {

    @Test
    void allow_WhenClosed_ReturnsTrue() {
        LlmPurposeCircuitBreaker breaker = new LlmPurposeCircuitBreaker(
                "test-purpose", 3, Duration.ofSeconds(30), Duration.ofMinutes(1));

        assertTrue(breaker.allow());
        assertEquals(LlmPurposeCircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void recordFailure_IncrementsConsecutiveFailures() {
        LlmPurposeCircuitBreaker breaker = new LlmPurposeCircuitBreaker(
                "test-purpose", 5, Duration.ofSeconds(30), Duration.ofMinutes(1));

        breaker.recordFailure();

        assertEquals(1, breaker.getConsecutiveFailures());
        assertEquals(LlmPurposeCircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void recordFailure_OpensCircuit_WhenThresholdReached() {
        LlmPurposeCircuitBreaker breaker = new LlmPurposeCircuitBreaker(
                "test-purpose", 3, Duration.ofSeconds(30), Duration.ofMinutes(1));

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        assertEquals(LlmPurposeCircuitBreaker.State.OPEN, breaker.getState());
        assertFalse(breaker.allow());
    }

    @Test
    void allow_ReturnsFalse_WhenOpen() {
        LlmPurposeCircuitBreaker breaker = new LlmPurposeCircuitBreaker(
                "test-purpose", 1, Duration.ofSeconds(30), Duration.ofMinutes(1));

        breaker.recordFailure();

        assertFalse(breaker.allow());
    }

    @Test
    void recordSuccess_ResetsConsecutiveFailures() {
        LlmPurposeCircuitBreaker breaker = new LlmPurposeCircuitBreaker(
                "test-purpose", 5, Duration.ofSeconds(30), Duration.ofMinutes(1));

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();

        assertEquals(0, breaker.getConsecutiveFailures());
        assertEquals(LlmPurposeCircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void allow_AllowsTestRequest_WhenHalfOpen() throws InterruptedException {
        LlmPurposeCircuitBreaker breaker = new LlmPurposeCircuitBreaker(
                "test-purpose", 1, Duration.ofMillis(50), Duration.ofMinutes(1));

        breaker.recordFailure();
        assertEquals(LlmPurposeCircuitBreaker.State.OPEN, breaker.getState());

        // Wait for open duration to expire
        Thread.sleep(100);

        // Should transition to half-open and allow one request
        assertTrue(breaker.allow());
        assertEquals(LlmPurposeCircuitBreaker.State.HALF_OPEN, breaker.getState());
    }

    @Test
    void allow_AllowsOnlyOneProbeInHalfOpen() throws InterruptedException {
        LlmPurposeCircuitBreaker breaker = new LlmPurposeCircuitBreaker(
                "test-purpose", 1, Duration.ofMillis(50), Duration.ofMinutes(1));

        breaker.recordFailure();
        assertEquals(LlmPurposeCircuitBreaker.State.OPEN, breaker.getState());

        // Wait for open duration to expire
        Thread.sleep(100);

        // First request should be allowed and transition to HALF_OPEN
        assertTrue(breaker.allow());
        assertEquals(LlmPurposeCircuitBreaker.State.HALF_OPEN, breaker.getState());

        // Second request should be blocked (probe already sent)
        assertFalse(breaker.allow());
        // Third request should also be blocked
        assertFalse(breaker.allow());
    }

    @Test
    void allow_ConcurrentTransitionFromOpen_OnlyOneProbeAllowed() throws Exception {
        LlmPurposeCircuitBreaker breaker = new LlmPurposeCircuitBreaker(
                "test-purpose", 1, Duration.ofMillis(10), Duration.ofMinutes(1));

        breaker.recordFailure();
        assertEquals(LlmPurposeCircuitBreaker.State.OPEN, breaker.getState());

        // Wait for open duration to expire so threads can transition
        Thread.sleep(50);

        // Simulate concurrent requests trying to get through
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean allowed = breaker.allow();
                    results.add(allowed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads at once
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Count how many threads were allowed
        long allowedCount = results.stream().filter(b -> b).count();

        // Only ONE thread should have been allowed through
        assertEquals(1, allowedCount,
            "Exactly one probe request should be allowed in HALF_OPEN state, but got " + allowedCount);
        assertEquals(LlmPurposeCircuitBreaker.State.HALF_OPEN, breaker.getState());
    }

    @Test
    void recordSuccess_ClosesCircuit_WhenHalfOpen() throws InterruptedException {
        LlmPurposeCircuitBreaker breaker = new LlmPurposeCircuitBreaker(
                "test-purpose", 1, Duration.ofMillis(50), Duration.ofMinutes(1));

        breaker.recordFailure();
        Thread.sleep(100);
        breaker.allow(); // Transition to half-open

        breaker.recordSuccess();

        assertEquals(LlmPurposeCircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void recordFailure_ReopensCircuit_WhenHalfOpen() throws InterruptedException {
        LlmPurposeCircuitBreaker breaker = new LlmPurposeCircuitBreaker(
                "test-purpose", 1, Duration.ofMillis(50), Duration.ofMinutes(1));

        breaker.recordFailure();
        Thread.sleep(100);
        breaker.allow(); // Transition to half-open

        breaker.recordFailure();

        assertEquals(LlmPurposeCircuitBreaker.State.OPEN, breaker.getState());
    }

    @Test
    void getPurpose_ReturnsConfiguredPurpose() {
        LlmPurposeCircuitBreaker breaker = new LlmPurposeCircuitBreaker(
                "interview_question_generation", 3, Duration.ofSeconds(30), Duration.ofMinutes(1));

        assertEquals("interview_question_generation", breaker.getPurpose());
    }
}