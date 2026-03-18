package com.deepdive.month05.week20;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Week 20: High-Performance & Low-Latency Patterns
 *
 * CONCEPT: When microseconds matter (trading, gaming, real-time control systems),
 * standard Java patterns may introduce unacceptable latency.
 *
 * Latency sources to eliminate:
 * 1. GC pauses:      Stop-the-world pauses (use ZGC or avoid allocation)
 * 2. Lock contention: Threads waiting for synchronized blocks
 * 3. Cache misses:   CPU cache line eviction (false sharing, random access patterns)
 * 4. Memory copies:  Intermediate buffers, boxing/unboxing
 * 5. Thread context switches: OS scheduler overhead
 * 6. Virtual calls:  Polymorphic dispatch prevents JIT inlining
 *
 * Techniques:
 * - Object pooling:    Reuse objects instead of allocating (avoid GC)
 * - Off-heap memory:   sun.misc.Unsafe or ByteBuffer.allocateDirect (no GC)
 * - False sharing:     Pad objects to fill CPU cache line (64 bytes)
 * - Busy-wait loops:   Thread.onSpinWait() instead of OS park (sub-microsecond)
 * - Lock-free CAS:     AtomicLong/VarHandle instead of synchronized
 * - Mechanical sympathy: Align data structures to cache lines, sequential access
 *
 * Tools for low-latency measurement:
 * - JMH: Java Microbenchmark Harness (account for JIT warmup)
 * - async-profiler: CPU flame graphs, allocation profiles
 * - JFR: JVM Flight Recorder (low overhead, production safe)
 * - HdrHistogram: Record and analyze latency percentiles (p99, p999)
 */
public class HighFrequencyDemo {

    // ==================== FALSE SHARING ====================

    /**
     * CONCEPT: False sharing - two variables in the same CPU cache line (64 bytes)
     * cause unnecessary cache invalidation when different CPU cores write them.
     *
     * Thread 1 writes counter1: CPU invalidates the ENTIRE 64-byte cache line
     * Thread 2 reads counter2: Must reload the cache line (even though counter2 unchanged!)
     *
     * Solution: Pad the object to ensure each counter occupies its own cache line.
     * Java 8+ @Contended annotation (with -XX:-RestrictContended) does this automatically.
     */

    // BAD: counter1 and counter2 likely share a cache line
    static class UnpaddedCounters {
        volatile long counter1 = 0;
        volatile long counter2 = 0;
    }

    // GOOD: Each counter in its own 128-byte region (2 cache lines safety margin)
    static class PaddedCounters {
        // @jdk.internal.vm.annotation.Contended  // Java 9+ annotation (requires --add-opens)
        volatile long counter1 = 0;
        // 7 longs of padding = 56 bytes + 8 bytes counter1 = 64 bytes (one cache line)
        private long p1, p2, p3, p4, p5, p6, p7;

        volatile long counter2 = 0;
        private long p8, p9, p10, p11, p12, p13, p14;
    }

    static void demonstrateFalseSharing() throws InterruptedException {
        System.out.println("\n--- False Sharing (Cache Line Contention) ---");

        int ITERATIONS = 100_000_000;

        // Test with false sharing
        UnpaddedCounters unpadded = new UnpaddedCounters();
        long unpaddedTime = measureConcurrentIncrements(
                () -> unpadded.counter1++,
                () -> unpadded.counter2++,
                ITERATIONS
        );

        // Test without false sharing
        PaddedCounters padded = new PaddedCounters();
        long paddedTime = measureConcurrentIncrements(
                () -> padded.counter1++,
                () -> padded.counter2++,
                ITERATIONS
        );

        System.out.printf("Unpadded (false sharing):  %,d ms%n", unpaddedTime);
        System.out.printf("Padded   (no false sharing): %,d ms%n", paddedTime);
        System.out.printf("Speedup from padding: ~%.1fx%n",
                unpaddedTime > 0 ? (double) unpaddedTime / paddedTime : 1.0);
        System.out.println("Note: Results vary by CPU architecture (AMD vs Intel vs ARM)");
    }

    private static long measureConcurrentIncrements(Runnable task1, Runnable task2, int iterations)
            throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int i = 0; i < iterations; i++) task1.run();
            done.countDown();
        });
        Thread t2 = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int i = 0; i < iterations; i++) task2.run();
            done.countDown();
        });

        t1.start(); t2.start();
        long startTime = System.currentTimeMillis();
        start.countDown();
        done.await();
        return System.currentTimeMillis() - startTime;
    }

    // ==================== OBJECT POOLING ====================

    /**
     * CONCEPT: Object pooling - reuse expensive-to-create objects instead of
     * allocating new ones (which pressures the GC).
     *
     * Good for: ByteBuffers, database connections, thread-local parsing objects,
     *           network connections, calendar/formatter objects.
     *
     * Bad for: Simple value objects (let escape analysis eliminate them instead).
     */
    static class ObjectPool<T> {
        private final ConcurrentLinkedQueue<T> pool = new ConcurrentLinkedQueue<>();
        private final java.util.function.Supplier<T> factory;
        private final java.util.function.Consumer<T> reset;
        private final AtomicInteger created = new AtomicInteger(0);
        private final AtomicInteger reused = new AtomicInteger(0);

        ObjectPool(java.util.function.Supplier<T> factory, java.util.function.Consumer<T> reset) {
            this.factory = factory;
            this.reset = reset;
        }

        T acquire() {
            T obj = pool.poll();
            if (obj != null) {
                reused.incrementAndGet();
                reset.accept(obj); // Reset state before reuse
                return obj;
            }
            created.incrementAndGet();
            return factory.get();
        }

        void release(T obj) {
            pool.offer(obj);
        }

        void printStats() {
            System.out.printf("  Pool stats: created=%d, reused=%d (reuse rate=%.1f%%)%n",
                    created.get(), reused.get(),
                    (created.get() + reused.get()) > 0 ?
                            (double) reused.get() / (created.get() + reused.get()) * 100 : 0);
        }
    }

    static void demonstrateObjectPooling() {
        System.out.println("\n--- Object Pooling (Reduce GC Pressure) ---");

        // Example: Pool of StringBuilder objects (common in high-throughput string building)
        ObjectPool<StringBuilder> sbPool = new ObjectPool<>(
                () -> new StringBuilder(256),      // Factory
                sb -> sb.setLength(0)              // Reset before reuse
        );

        // Without pooling: 10000 new StringBuilders
        long nopoolStart = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("Request-").append(i).append("-data");
            // sb becomes garbage immediately -> GC pressure
        }
        long nopoolTime = System.nanoTime() - nopoolStart;

        // With pooling: reuse StringBuilders
        long poolStart = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            StringBuilder sb = sbPool.acquire();
            sb.append("Request-").append(i).append("-data");
            sbPool.release(sb); // Return to pool
        }
        long poolTime = System.nanoTime() - poolStart;

        System.out.printf("Without pooling: %,d µs%n", nopoolTime / 1000);
        System.out.printf("With pooling:    %,d µs%n", poolTime / 1000);
        sbPool.printStats();
        System.out.println("Note: Pooling benefits increase under GC pressure (larger heaps, more objects)");
    }

    // ==================== BUSY-WAIT VS BLOCKING ====================

    /**
     * CONCEPT: Busy-wait (spin-wait) - thread actively polls for a condition.
     * More responsive than blocking (microseconds vs milliseconds),
     * but burns CPU even when idle.
     *
     * Use case: Ultra-low latency trading systems where microsecond latency
     *           matters more than CPU efficiency.
     *
     * Thread.onSpinWait(): Hint to JVM to use CPU-pause instruction (avoids wasted speculation)
     */
    static void demonstrateBusyWait() throws InterruptedException {
        System.out.println("\n--- Busy-Wait vs Blocking ---");

        AtomicBoolean flag = new AtomicBoolean(false);
        AtomicLong detectionLatencyNanos = new AtomicLong(0);

        // Test 1: Blocking wait (Thread.sleep / wait/notify)
        Thread blockingThread = new Thread(() -> {
            synchronized (flag) {
                try {
                    while (!flag.get()) {
                        flag.wait(10); // OS blocks this thread
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        long signalTime1 = System.nanoTime();
        blockingThread.start();
        Thread.sleep(10);
        flag.set(true);
        synchronized (flag) { flag.notifyAll(); }
        blockingThread.join();
        long blockingLatency = System.nanoTime() - signalTime1;

        // Test 2: Busy-wait (spin-wait)
        flag.set(false);
        Thread busyThread = new Thread(() -> {
            long start = System.nanoTime();
            while (!flag.get()) {
                Thread.onSpinWait(); // CPU hint: spin efficiently
            }
            detectionLatencyNanos.set(System.nanoTime() - start);
        });

        busyThread.start();
        Thread.sleep(10);
        long signalTime2 = System.nanoTime();
        flag.set(true);
        busyThread.join();

        System.out.printf("Blocking detection latency:  ~%,d µs%n", blockingLatency / 1000);
        System.out.printf("Busy-wait detection latency: ~%,d ns (< 1µs)%n", detectionLatencyNanos.get());
        System.out.println("Busy-wait: MUCH faster but uses 100% CPU of one core");
        System.out.println("Use only for latency-critical paths in dedicated threads");
    }

    // ==================== MEMORY ACCESS PATTERNS ====================

    /**
     * CONCEPT: Sequential vs random memory access patterns.
     * CPUs prefetch sequential memory (cache-friendly).
     * Random access causes cache misses (100-300 CPU cycles each).
     *
     * "Mechanical sympathy" - write code that works with CPU hardware, not against it.
     */
    static void demonstrateMemoryAccessPatterns() {
        System.out.println("\n--- Memory Access Patterns (Cache Friendliness) ---");

        int SIZE = 1000;
        int[][] matrix = new int[SIZE][SIZE];
        // Fill matrix
        for (int i = 0; i < SIZE; i++) for (int j = 0; j < SIZE; j++) matrix[i][j] = i * SIZE + j;

        // Row-major (sequential, cache-friendly)
        long rowStart = System.nanoTime();
        long rowSum = 0;
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++)
                rowSum += matrix[i][j]; // Sequential access (each row is contiguous)
        long rowTime = System.nanoTime() - rowStart;

        // Column-major (strided, cache-unfriendly)
        long colStart = System.nanoTime();
        long colSum = 0;
        for (int j = 0; j < SIZE; j++)
            for (int i = 0; i < SIZE; i++)
                colSum += matrix[i][j]; // Strided access (jumps 4*SIZE bytes each step)
        long colTime = System.nanoTime() - colStart;

        System.out.printf("Row-major (sequential, cache-friendly):  %,d ms (sum=%d)%n",
                rowTime / 1_000_000, rowSum);
        System.out.printf("Col-major (strided, cache-unfriendly):   %,d ms (sum=%d)%n",
                colTime / 1_000_000, colSum);
        System.out.printf("Cache-friendly speedup: ~%.1fx%n",
                colTime > 0 ? (double) colTime / rowTime : 1.0);
        System.out.println("CPU cache line = 64 bytes = 16 ints. Sequential reads = 1 cache miss / 16 reads");
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== High-Frequency / Low-Latency Patterns Demo ===");

        demonstrateFalseSharing();
        demonstrateObjectPooling();
        demonstrateBusyWait();
        demonstrateMemoryAccessPatterns();

        System.out.println("\n--- Staff Engineer Tips for Low Latency ---");
        System.out.println("1. Measure first! Never guess where latency comes from.");
        System.out.println("   Use: async-profiler, JFR, HdrHistogram for p99/p999 latencies");
        System.out.println("2. Minimize allocations in hot paths (reduces GC pauses)");
        System.out.println("   Profile with: -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining");
        System.out.println("3. Use ZGC or Shenandoah for sub-millisecond GC pauses");
        System.out.println("   Experiment: -XX:+UseZGC -Xmx8g");
        System.out.println("4. Pin threads to cores (CPU affinity) for trading systems");
        System.out.println("5. Use off-heap storage (ByteBuffer.allocateDirect) for large data");
        System.out.println("6. Java 21 virtual threads: excellent for I/O latency, not CPU latency");
    }
}
