package com.deepdive.month03.week12;

import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Week 12: Retry with Exponential Backoff
 *
 * CONCEPT: Transient failures (network blips, temporary overload) can be resolved
 * by retrying. Exponential backoff prevents thundering herd problems where all
 * clients retry simultaneously and overwhelm the recovering service.
 *
 * Retry strategies:
 * 1. Fixed delay:       Wait 1s between each retry
 * 2. Linear backoff:    Wait 1s, 2s, 3s, 4s...
 * 3. Exponential:       Wait 1s, 2s, 4s, 8s, 16s... (base^attempt)
 * 4. Exponential + jitter: Randomize the wait to spread retries from multiple clients
 *
 * WHY jitter? Without jitter:
 * - 1000 clients all fail at time T
 * - All retry at exactly T+1s, T+2s, T+4s...
 * - Creates synchronized waves of traffic ("thundering herd")
 *
 * With jitter: each client retries at a slightly different time
 * -> Traffic is spread out -> recovering service handles load gracefully
 *
 * Jitter strategies (AWS: https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/):
 * - Full Jitter:    delay = random(0, base^attempt)
 * - Equal Jitter:   delay = base^attempt/2 + random(0, base^attempt/2)
 * - Decorrelated:   delay = random(minDelay, prevDelay * 3)
 *
 * AWS recommends "Full Jitter" or "Decorrelated Jitter" for most cases.
 */
public class RetryPatternDemo {

    enum RetryResult { SUCCESS, EXHAUSTED, NOT_RETRYABLE }

    @FunctionalInterface
    interface RetryableOperation<T> {
        T execute() throws Exception;
    }

    static class RetryConfig {
        final int maxAttempts;
        final long initialDelayMs;
        final long maxDelayMs;
        final double multiplier;
        final boolean useJitter;

        RetryConfig(int maxAttempts, long initialDelayMs, long maxDelayMs,
                    double multiplier, boolean useJitter) {
            this.maxAttempts = maxAttempts;
            this.initialDelayMs = initialDelayMs;
            this.maxDelayMs = maxDelayMs;
            this.multiplier = multiplier;
            this.useJitter = useJitter;
        }

        static RetryConfig exponentialWithJitter() {
            return new RetryConfig(5, 100, 30_000, 2.0, true);
        }

        static RetryConfig fixed() {
            return new RetryConfig(3, 1000, 1000, 1.0, false);
        }
    }

    /**
     * CONCEPT: Core retry executor with configurable backoff strategy.
     */
    static class RetryExecutor {
        private final RetryConfig config;
        private final Random random = new Random();

        RetryExecutor(RetryConfig config) {
            this.config = config;
        }

        <T> T execute(RetryableOperation<T> operation) throws Exception {
            Exception lastException = null;
            long delay = config.initialDelayMs;

            for (int attempt = 1; attempt <= config.maxAttempts; attempt++) {
                try {
                    T result = operation.execute();
                    if (attempt > 1) {
                        System.out.printf("  Succeeded on attempt %d (after %d failures)%n",
                                attempt, attempt - 1);
                    }
                    return result;
                } catch (NonRetryableException e) {
                    // CONCEPT: Some errors should NOT be retried (e.g., 4xx HTTP errors)
                    System.out.println("  Non-retryable error: " + e.getMessage() + " (giving up immediately)");
                    throw e;
                } catch (Exception e) {
                    lastException = e;
                    if (attempt == config.maxAttempts) {
                        System.out.printf("  Attempt %d/%d failed: %s (exhausted retries)%n",
                                attempt, config.maxAttempts, e.getMessage());
                        break;
                    }

                    long actualDelay = computeDelay(attempt, delay);
                    System.out.printf("  Attempt %d/%d failed: %s. Waiting %dms before retry...%n",
                            attempt, config.maxAttempts, e.getMessage(), actualDelay);

                    Thread.sleep(actualDelay);
                    delay = Math.min((long)(delay * config.multiplier), config.maxDelayMs);
                }
            }

            throw new Exception("All " + config.maxAttempts + " attempts failed. Last error: " +
                    (lastException != null ? lastException.getMessage() : "unknown"), lastException);
        }

        private long computeDelay(int attempt, long baseDelay) {
            if (!config.useJitter) return baseDelay;

            // CONCEPT: Full jitter - random value between 0 and base delay
            // This spreads retries from multiple clients across the full interval
            return (long)(random.nextDouble() * baseDelay);
        }
    }

    static class NonRetryableException extends RuntimeException {
        final int statusCode;
        NonRetryableException(String msg, int statusCode) {
            super(msg);
            this.statusCode = statusCode;
        }
    }

    // Simulated HTTP service with transient failures
    static class FlakyHttpService {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final int failFirstN; // Fail this many times before succeeding

        FlakyHttpService(int failFirstN) {
            this.failFirstN = failFirstN;
        }

        String call(String endpoint) {
            int call = callCount.incrementAndGet();
            if (call <= failFirstN) {
                throw new RuntimeException("HTTP 503 Service Unavailable (call #" + call + ")");
            }
            return "HTTP 200 OK from " + endpoint + " (call #" + call + ")";
        }

        String callWithBadRequest(String endpoint) {
            throw new NonRetryableException("HTTP 400 Bad Request: invalid parameters", 400);
        }
    }

    /**
     * CONCEPT: Retry budget - limit the total time budget for retries,
     * not just the number of attempts. Important for SLA management.
     */
    static class TimeBoundedRetry {
        private final long totalBudgetMs;
        private final long initialDelayMs;
        private final double multiplier;
        private final Random random = new Random();

        TimeBoundedRetry(long totalBudgetMs, long initialDelayMs, double multiplier) {
            this.totalBudgetMs = totalBudgetMs;
            this.initialDelayMs = initialDelayMs;
            this.multiplier = multiplier;
        }

        <T> T execute(RetryableOperation<T> operation) throws Exception {
            long deadline = System.currentTimeMillis() + totalBudgetMs;
            long delay = initialDelayMs;
            int attempt = 0;
            Exception last = null;

            while (System.currentTimeMillis() < deadline) {
                attempt++;
                try {
                    return operation.execute();
                } catch (Exception e) {
                    last = e;
                    long remainingMs = deadline - System.currentTimeMillis();
                    if (remainingMs <= 0) break;

                    // Don't wait longer than remaining budget
                    long jitteredDelay = (long)(random.nextDouble() * delay);
                    long actualDelay = Math.min(jitteredDelay, remainingMs);

                    System.out.printf("  Budget-aware retry attempt %d failed, waiting %dms (budget: %dms left)%n",
                            attempt, actualDelay, remainingMs);

                    if (actualDelay > 0) Thread.sleep(actualDelay);
                    delay = Math.min((long)(delay * multiplier), 5000);
                }
            }
            throw new TimeoutException("Retry budget exhausted after " + attempt + " attempts. Last: " +
                    (last != null ? last.getMessage() : "unknown"));
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Retry Pattern Demo ===");

        demonstrateExponentialBackoff();
        demonstrateJitterEffect();
        demonstrateNonRetryable();
        demonstrateTimeBoundedRetry();
        compareBackoffStrategies();
    }

    private static void demonstrateExponentialBackoff() throws Exception {
        System.out.println("\n--- Exponential Backoff with Jitter ---");
        FlakyHttpService service = new FlakyHttpService(3); // Fail first 3 calls
        RetryExecutor executor = new RetryExecutor(RetryConfig.exponentialWithJitter());

        try {
            String result = executor.execute(() -> service.call("/api/payments"));
            System.out.println("Final result: " + result);
        } catch (Exception e) {
            System.out.println("All retries exhausted: " + e.getMessage());
        }
    }

    private static void demonstrateJitterEffect() throws InterruptedException {
        System.out.println("\n--- Jitter Effect (Preventing Thundering Herd) ---");
        System.out.println("Simulating 5 clients retrying simultaneously at t=0:");

        int numClients = 5;
        CountDownLatch ready = new CountDownLatch(numClients);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> retryTimes = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    long baseDelay = 1000; // 1 second base
                    long jittered = (long)(Math.random() * baseDelay);
                    retryTimes.add(jittered);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
        ready.await();
        start.countDown();
        Thread.sleep(200);

        Collections.sort(retryTimes);
        System.out.println("Retry times with full jitter (spread over 0-1000ms):");
        retryTimes.forEach(t -> System.out.printf("  Client retries at: %dms%n", t));

        // Without jitter: all at same time
        System.out.println("Without jitter: all 5 clients would retry at exactly 1000ms (thundering herd!)");
    }

    private static void demonstrateNonRetryable() {
        System.out.println("\n--- Non-Retryable Errors ---");
        FlakyHttpService service = new FlakyHttpService(0);
        RetryExecutor executor = new RetryExecutor(RetryConfig.exponentialWithJitter());

        try {
            executor.execute(() -> service.callWithBadRequest("/api/orders"));
        } catch (NonRetryableException e) {
            System.out.println("Correctly not retried: " + e.getMessage());
            System.out.println("HTTP 4xx errors = client error = no point retrying");
            System.out.println("HTTP 5xx errors = server error = may retry");
        } catch (Exception e) {
            System.out.println("Unexpected: " + e.getMessage());
        }
    }

    private static void demonstrateTimeBoundedRetry() throws Exception {
        System.out.println("\n--- Time-Bounded Retry (SLA-aware) ---");
        FlakyHttpService service = new FlakyHttpService(10); // Will always fail within budget
        TimeBoundedRetry retry = new TimeBoundedRetry(500, 50, 2.0); // 500ms budget

        try {
            retry.execute(() -> service.call("/api/reports"));
        } catch (TimeoutException e) {
            System.out.println("Budget exceeded: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
        System.out.println("Time-bounded retry: respects SLA even with many transient failures.");
    }

    private static void compareBackoffStrategies() {
        System.out.println("\n--- Backoff Strategy Comparison ---");
        System.out.println("Strategy delays for attempts 1-6 (base=1000ms, multiplier=2):");
        System.out.printf("  %-20s %s%n", "Strategy", "Delays (ms)");
        System.out.println("  " + "-".repeat(60));

        long base = 1000;
        int attempts = 6;
        Random rng = new Random(42);

        // Fixed
        System.out.print("  Fixed:               ");
        for (int i = 0; i < attempts; i++) System.printf("%5d", base);
        System.out.println();

        // Exponential no jitter
        System.out.print("  Exponential (noJ):   ");
        long delay = base;
        for (int i = 0; i < attempts; i++) {
            System.printf("%5d", Math.min(delay, 30_000L));
            delay *= 2;
        }
        System.out.println();

        // Exponential full jitter
        System.out.print("  Exponential (fullJ): ");
        delay = base;
        for (int i = 0; i < attempts; i++) {
            System.printf("%5d", (long)(rng.nextDouble() * Math.min(delay, 30_000L)));
            delay *= 2;
        }
        System.out.println();

        System.out.println("\nFull jitter provides best spread - reduces synchronized retries");
        System.out.println("by ~50% compared to no-jitter exponential backoff.");
    }
}
