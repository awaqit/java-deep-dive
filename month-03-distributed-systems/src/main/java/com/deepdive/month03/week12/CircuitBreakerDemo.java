package com.deepdive.month03.week12;

import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/**
 * Week 12: Circuit Breaker Pattern
 *
 * CONCEPT: The Circuit Breaker prevents cascading failures in distributed systems.
 * Named after electrical circuit breakers: when a service is failing, "open" the
 * circuit to stop calls (preventing overload) and allow the service to recover.
 *
 * States:
 * CLOSED:    Normal operation. Requests flow through. Failures counted.
 *            -> Transitions to OPEN when failure rate exceeds threshold
 *
 * OPEN:      Service is failing. Requests are IMMEDIATELY REJECTED (fail fast).
 *            -> Transitions to HALF_OPEN after resetTimeout
 *            -> WHY: Let the failing service recover without more load!
 *
 * HALF_OPEN: Probe state. Allow a few requests to test if service recovered.
 *            -> CLOSED if probe succeeds (service is healthy)
 *            -> OPEN if probe fails (service still failing)
 *
 *    [CLOSED] --failure_threshold--> [OPEN] --reset_timeout--> [HALF_OPEN]
 *       ^                                                            |
 *       |_________________success____________________________________|
 *       |_________________failure____________________________________|-> [OPEN]
 *
 * Real implementations: Resilience4j, Hystrix (Netflix, deprecated), Istio circuit breaker
 *
 * Staff Engineer note: Circuit breakers are often combined with:
 * - Bulkhead: Limit concurrent calls to a service (isolate thread pools)
 * - Timeout: Don't wait indefinitely for a response
 * - Retry: Retry with exponential backoff (see RetryPatternDemo)
 */
public class CircuitBreakerDemo {

    enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    static class CircuitBreaker {
        private final String name;
        private final int failureThreshold;
        private final int halfOpenMaxAttempts;
        private final long resetTimeoutMs;

        private volatile CircuitState state = CircuitState.CLOSED;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);
        private volatile long openedAt = 0L;

        // Metrics
        private final AtomicInteger totalRequests = new AtomicInteger(0);
        private final AtomicInteger rejectedRequests = new AtomicInteger(0);

        CircuitBreaker(String name, int failureThreshold, int halfOpenMaxAttempts, long resetTimeoutMs) {
            this.name = name;
            this.failureThreshold = failureThreshold;
            this.halfOpenMaxAttempts = halfOpenMaxAttempts;
            this.resetTimeoutMs = resetTimeoutMs;
        }

        /**
         * CONCEPT: Execute a protected call through the circuit breaker.
         * The circuit breaker intercepts the call and enforces the state machine.
         */
        <T> T execute(Supplier<T> supplier) throws Exception {
            totalRequests.incrementAndGet();

            switch (state) {
                case OPEN -> {
                    if (isResetTimeoutElapsed()) {
                        transitionTo(CircuitState.HALF_OPEN);
                        // Fall through to HALF_OPEN handling
                        return executeInHalfOpen(supplier);
                    }
                    rejectedRequests.incrementAndGet();
                    throw new CircuitOpenException("[" + name + "] Circuit OPEN - fast fail! " +
                            "Retry after " + remainingTimeMs() + "ms");
                }
                case HALF_OPEN -> {
                    return executeInHalfOpen(supplier);
                }
                case CLOSED -> {
                    return executeAndRecord(supplier);
                }
            }
            throw new IllegalStateException("Unknown state: " + state);
        }

        private <T> T executeInHalfOpen(Supplier<T> supplier) throws Exception {
            int attempts = halfOpenAttempts.incrementAndGet();
            if (attempts > halfOpenMaxAttempts) {
                rejectedRequests.incrementAndGet();
                throw new CircuitOpenException("[" + name + "] HALF_OPEN: max probe attempts exceeded");
            }
            try {
                T result = supplier.get();
                // CONCEPT: Success in HALF_OPEN = service recovered, close circuit
                if (halfOpenAttempts.get() >= halfOpenMaxAttempts ||
                        successCount.incrementAndGet() >= halfOpenMaxAttempts / 2) {
                    transitionTo(CircuitState.CLOSED);
                }
                return result;
            } catch (Exception e) {
                // CONCEPT: Failure in HALF_OPEN = service still failing, reopen
                transitionTo(CircuitState.OPEN);
                throw e;
            }
        }

        private <T> T executeAndRecord(Supplier<T> supplier) throws Exception {
            try {
                T result = supplier.get();
                onSuccess();
                return result;
            } catch (Exception e) {
                onFailure();
                throw e;
            }
        }

        private void onSuccess() {
            failureCount.set(0); // Reset on success (sliding window variant would be different)
            successCount.incrementAndGet();
        }

        private void onFailure() {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold && state == CircuitState.CLOSED) {
                transitionTo(CircuitState.OPEN);
            }
        }

        private synchronized void transitionTo(CircuitState newState) {
            CircuitState oldState = state;
            if (oldState == newState) return;
            state = newState;
            if (newState == CircuitState.OPEN) {
                openedAt = System.currentTimeMillis();
                halfOpenAttempts.set(0);
                successCount.set(0);
            } else if (newState == CircuitState.CLOSED) {
                failureCount.set(0);
                halfOpenAttempts.set(0);
                successCount.set(0);
            }
            System.out.printf("  [%s] Circuit transition: %s -> %s%n", name, oldState, newState);
        }

        private boolean isResetTimeoutElapsed() {
            return System.currentTimeMillis() - openedAt > resetTimeoutMs;
        }

        private long remainingTimeMs() {
            return Math.max(0, resetTimeoutMs - (System.currentTimeMillis() - openedAt));
        }

        CircuitState getState() { return state; }
        void printStats() {
            System.out.printf("  [%s] state=%s total=%d rejected=%d failures=%d%n",
                    name, state, totalRequests.get(), rejectedRequests.get(), failureCount.get());
        }
    }

    static class CircuitOpenException extends RuntimeException {
        CircuitOpenException(String msg) { super(msg); }
    }

    // Simulated flaky service
    static class FlakyPaymentService {
        private int callCount = 0;
        private boolean failing = false;

        void setFailing(boolean failing) { this.failing = failing; }

        String charge(double amount) {
            callCount++;
            if (failing) throw new RuntimeException("Payment service unavailable (timeout)");
            return "CHARGED: $" + amount + " (call #" + callCount + ")";
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Circuit Breaker Pattern Demo ===");

        demonstrateCircuitBreakerLifecycle();
        demonstrateWithFallback();
    }

    private static void demonstrateCircuitBreakerLifecycle() throws InterruptedException {
        System.out.println("\n--- Circuit Breaker Lifecycle ---");

        CircuitBreaker cb = new CircuitBreaker("payment-cb", 3, 2, 500);
        FlakyPaymentService paymentService = new FlakyPaymentService();

        System.out.println("\n[Phase 1: Normal operation - CLOSED]");
        for (int i = 0; i < 3; i++) {
            try {
                String result = cb.execute(() -> paymentService.charge(100.0 + i));
                System.out.println("  Success: " + result);
            } catch (Exception e) {
                System.out.println("  Failed: " + e.getMessage());
            }
        }
        cb.printStats();

        System.out.println("\n[Phase 2: Service fails - approaching threshold]");
        paymentService.setFailing(true);
        for (int i = 0; i < 4; i++) {
            try {
                cb.execute(() -> paymentService.charge(50.0));
                System.out.println("  Success");
            } catch (CircuitOpenException e) {
                System.out.println("  CIRCUIT OPEN (fast fail): " + e.getMessage());
            } catch (Exception e) {
                System.out.println("  Service failed: " + e.getMessage());
            }
        }
        cb.printStats();

        System.out.println("\n[Phase 3: Circuit OPEN - all calls rejected immediately]");
        for (int i = 0; i < 3; i++) {
            try {
                cb.execute(() -> paymentService.charge(200.0));
            } catch (CircuitOpenException e) {
                System.out.println("  Fast fail: circuit is OPEN");
            } catch (Exception e) {
                System.out.println("  Error: " + e.getMessage());
            }
        }
        cb.printStats();

        System.out.println("\n[Phase 4: Wait for reset timeout, then HALF_OPEN probe]");
        System.out.println("  Waiting 600ms for reset timeout...");
        Thread.sleep(600);

        // Service recovers
        paymentService.setFailing(false);
        for (int i = 0; i < 3; i++) {
            try {
                String result = cb.execute(() -> paymentService.charge(75.0));
                System.out.println("  Probe success: " + result);
            } catch (Exception e) {
                System.out.println("  Probe failed: " + e.getMessage());
            }
        }
        cb.printStats();
    }

    /**
     * CONCEPT: Circuit breaker with fallback - provide degraded service instead of failing.
     * Common patterns:
     * - Return cached/stale data
     * - Return default/empty response
     * - Route to backup service
     * - Queue the operation for retry later
     */
    private static void demonstrateWithFallback() throws InterruptedException {
        System.out.println("\n--- Circuit Breaker with Fallback ---");

        CircuitBreaker cb = new CircuitBreaker("recommendation-cb", 2, 1, 300);
        AtomicInteger failureCount = new AtomicInteger(0);

        // Flaky recommendation service
        Supplier<String[]> primaryService = () -> {
            if (failureCount.get() > 0) throw new RuntimeException("Recommendation service down");
            return new String[]{"Product A", "Product B", "Product C"};
        };

        // Fallback: return popular products from cache
        Supplier<String[]> fallbackService = () -> {
            System.out.println("  [FALLBACK] Using cached popular products");
            return new String[]{"Bestseller 1", "Bestseller 2", "Bestseller 3"};
        };

        // Helper to execute with fallback
        String[] getRecommendations(String userId) throws Exception {
            // Not a lambda - using local method pattern for clarity
            return null;
        };

        for (String phase : new String[]{"normal", "failing", "recovery"}) {
            if (phase.equals("failing")) failureCount.set(1);
            if (phase.equals("recovery")) { failureCount.set(0); Thread.sleep(400); }

            System.out.println("\n  Phase: " + phase);
            for (int i = 0; i < 3; i++) {
                String[] recommendations;
                try {
                    recommendations = cb.execute(primaryService);
                    System.out.println("  Got " + recommendations.length + " primary recommendations");
                } catch (Exception e) {
                    // Fallback on circuit open or service error
                    recommendations = fallbackService.get();
                    System.out.println("  Using fallback: " + java.util.Arrays.toString(recommendations));
                }
            }
        }

        System.out.println("\nCircuit breaker prevents cascading failures while fallback provides");
        System.out.println("degraded-but-functional service. Key: always have a fallback!");
    }
}
