package com.deepdive.month01.week02;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Random;

/**
 * Week 2: HotSpot Profiling Patterns
 *
 * CONCEPT: Profiling identifies where time and memory are being spent.
 * The HotSpot JVM provides rich profiling APIs for building APM tools.
 *
 * Types of profiling:
 * - CPU profiling:    Where is the CPU time being spent?
 * - Allocation profiling: Which code paths allocate the most objects?
 * - Lock profiling:   Which locks are causing contention?
 * - I/O profiling:    Where is the program blocked waiting for I/O?
 *
 * JVM Built-in profiling tools:
 * - Java Flight Recorder (JFR): Low-overhead, production-safe profiler
 *   jcmd <pid> JFR.start duration=60s filename=recording.jfr
 *   jfr print --events jdk.CPUSample recording.jfr
 *
 * - JVM Tool Interface (JVMTI): Native C API for profiler agents
 *   async-profiler, YourKit, JProfiler all use JVMTI
 *
 * - AsyncGetCallTrace: Safe-point-free stack sampling (used by async-profiler)
 *   Avoids "safe-point bias" - important for accurate CPU profiles
 *
 * - ThreadMXBean: Thread CPU time, blocked time, contention statistics
 *
 * Staff Engineer tip: Use async-profiler for production profiling:
 *   ./profiler.sh -e cpu -d 30 -f flame.html <pid>
 *   ./profiler.sh -e alloc -d 30 -f alloc.html <pid>
 */
public class HotSpotProfilerExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== HotSpot Profiler Patterns ===");

        demonstrateCpuTimeMeasurement();
        demonstrateHotMethodIdentification();
        demonstrateAllocationHotspot();
        demonstrateLockContention();
    }

    /**
     * CONCEPT: ThreadMXBean.getThreadCpuTime() measures CPU time spent in a thread.
     * This is useful for attributing CPU cost to specific operations.
     *
     * WHY: Wall-clock time includes I/O waits; CPU time shows actual computation cost.
     * In a multi-tenant service, tracking per-request CPU time is crucial for billing.
     */
    private static void demonstrateCpuTimeMeasurement() throws InterruptedException {
        System.out.println("\n--- CPU Time Measurement ---");

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

        if (!threadMXBean.isCurrentThreadCpuTimeSupported()) {
            System.out.println("CPU time measurement not supported.");
            return;
        }

        threadMXBean.setThreadCpuTimeEnabled(true);

        long cpuStart = threadMXBean.getCurrentThreadCpuTime();
        long wallStart = System.nanoTime();

        // CPU-bound work
        long sum = 0;
        for (int i = 0; i < 10_000_000; i++) {
            sum += Math.sqrt(i); // computationally intensive
        }

        long cpuTime = threadMXBean.getCurrentThreadCpuTime() - cpuStart;
        long wallTime = System.nanoTime() - wallStart;

        System.out.printf("CPU-bound operation (sum=%.0f):%n", (double) sum);
        System.out.printf("  Wall-clock time: %,d ms%n", wallTime / 1_000_000);
        System.out.printf("  CPU time:        %,d ms%n", cpuTime / 1_000_000);
        System.out.printf("  CPU utilization: %.1f%%%n", (double) cpuTime / wallTime * 100);

        // I/O-bound simulation (sleep)
        long cpuStart2 = threadMXBean.getCurrentThreadCpuTime();
        long wallStart2 = System.nanoTime();

        Thread.sleep(50); // Simulates I/O wait

        long cpuTime2 = threadMXBean.getCurrentThreadCpuTime() - cpuStart2;
        long wallTime2 = System.nanoTime() - wallStart2;

        System.out.printf("%nI/O-bound operation (sleep 50ms):%n");
        System.out.printf("  Wall-clock time: %,d ms%n", wallTime2 / 1_000_000);
        System.out.printf("  CPU time:        %,d ms%n", cpuTime2 / 1_000_000);
        System.out.printf("  CPU utilization: %.1f%% (low because thread was sleeping)%n",
                wallTime2 > 0 ? (double) cpuTime2 / wallTime2 * 100 : 0);
    }

    /**
     * CONCEPT: Identifying hot methods through manual instrumentation.
     * In production, JFR's jdk.CPUSample event does this automatically with minimal overhead.
     *
     * WHY: Profiling guides optimization. Amdahl's law: if you optimize a section
     * that represents only 5% of runtime, even infinite speedup gives < 5% improvement.
     * Always profile before optimizing.
     */
    private static void demonstrateHotMethodIdentification() {
        System.out.println("\n--- Hot Method Identification ---");

        // Simulate a workload with uneven cost distribution
        long[] timings = new long[4];

        // Method A: fast (O(1) work)
        long start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) methodA(i);
        timings[0] = System.nanoTime() - start;

        // Method B: medium (O(n) work)
        start = System.nanoTime();
        for (int i = 0; i < 100; i++) methodB(i);
        timings[1] = System.nanoTime() - start;

        // Method C: slow (O(n log n) work) - the hot spot
        start = System.nanoTime();
        for (int i = 0; i < 10; i++) methodC(100 + i);
        timings[2] = System.nanoTime() - start;

        // Method D: medium-fast
        start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) methodD(i);
        timings[3] = System.nanoTime() - start;

        long total = Arrays.stream(timings).sum();
        String[] names = {"A (simple math x100k)", "B (linear scan x100)", "C (sort x10)", "D (string ops x10k)"};
        System.out.println("Time distribution:");
        for (int i = 0; i < names.length; i++) {
            System.out.printf("  %-30s %,8d ms  (%.1f%%)%n",
                    names[i], timings[i] / 1_000_000,
                    total > 0 ? (double) timings[i] / total * 100 : 0);
        }
        System.out.println("NOTE: Focus optimization effort on the highest percentage method.");
    }

    private static long methodA(int n) { return n * n + n; } // O(1)

    private static long methodB(int n) { // O(n)
        long sum = 0;
        for (int i = 0; i < n * 10; i++) sum += i;
        return sum;
    }

    private static void methodC(int n) { // O(n log n) - sort
        int[] arr = new Random(n).ints(n * 100).toArray();
        Arrays.sort(arr);
    }

    private static String methodD(int n) { // String operations
        return String.valueOf(n).repeat(3).substring(0, Math.min(3, String.valueOf(n).length()));
    }

    /**
     * CONCEPT: Allocation hotspot - identify which code paths allocate the most objects.
     * High allocation rates cause frequent GC, which adds latency.
     *
     * WHY: Reduce allocations in hot paths by:
     * - Object pooling (e.g., ByteBuffer pools)
     * - Reusing collections (clear() instead of new)
     * - Using primitive arrays instead of boxed collections
     * - Avoiding autoboxing in hot loops
     *
     * JFR event for this: jdk.ObjectAllocationInNewTLAB
     */
    private static void demonstrateAllocationHotspot() {
        System.out.println("\n--- Allocation Patterns ---");

        final int ITERATIONS = 1_000_000;

        // BAD: Autoboxing creates Integer objects in hot loop
        long badStart = System.nanoTime();
        long badSum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            Integer boxed = i; // AUTOBOXING: creates Integer object!
            badSum += boxed;   // UNBOXING: extracts int value
        }
        long badTime = System.nanoTime() - badStart;

        // GOOD: Use primitive int - zero allocations
        long goodStart = System.nanoTime();
        long goodSum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            goodSum += i; // Pure primitive math, no allocation
        }
        long goodTime = System.nanoTime() - goodStart;

        System.out.printf("Autoboxed Integer loop:  %,d ns (sum=%d)%n", badTime, badSum);
        System.out.printf("Primitive int loop:      %,d ns (sum=%d)%n", goodTime, goodSum);
        System.out.printf("Speedup from avoiding boxing: ~%.1fx%n",
                badTime > 0 ? (double) badTime / goodTime : 1.0);

        // NOTE: With JIT escape analysis, Integer may be optimized away. Results vary.
        System.out.println("NOTE: JIT may optimize the boxing case. Use JFR allocation profiler for certainty.");

        // CONCEPT: StringBuilder vs String concatenation in loops
        long concatStart = System.nanoTime();
        String result1 = "";
        for (int i = 0; i < 1000; i++) {
            result1 += i; // Creates new String on each iteration: O(n^2) allocations!
        }
        long concatTime = System.nanoTime() - concatStart;

        long sbStart = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append(i); // Amortized O(1) - reuses internal char array
        }
        String result2 = sb.toString();
        long sbTime = System.nanoTime() - sbStart;

        System.out.printf("%nString concat (1000x): %,d ms, length=%d%n", concatTime / 1_000_000, result1.length());
        System.out.printf("StringBuilder (1000x): %,d ms, length=%d%n", sbTime / 1_000_000, result2.length());
    }

    /**
     * CONCEPT: Lock contention profiling - ThreadMXBean.getThreadInfo() with
     * BLOCKED state analysis shows which threads are waiting on monitors.
     *
     * WHY: In high-concurrency systems, locks are a common bottleneck.
     * Lock profiling guides decisions on:
     * - Using concurrent data structures (ConcurrentHashMap, etc.)
     * - Reducing lock scope
     * - Using lock-free algorithms (CAS operations)
     * - Using virtual threads (Java 21) for I/O-bound workloads
     */
    private static void demonstrateLockContention() throws InterruptedException {
        System.out.println("\n--- Lock Contention Pattern ---");

        // Contended lock simulation
        Object sharedLock = new Object();
        long[] results = new long[4];

        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final int idx = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100_000; i++) {
                    synchronized (sharedLock) { // HIGH contention: all threads fight for same lock
                        results[idx]++;
                    }
                }
            }, "contended-" + t);
        }

        long start = System.nanoTime();
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        long contentedTime = System.nanoTime() - start;

        long total = Arrays.stream(results).sum();
        System.out.printf("Contended synchronized: %,d ms, total count=%d%n",
                contentedTime / 1_000_000, total);

        // Better: java.util.concurrent.atomic - lock-free CAS operations
        java.util.concurrent.atomic.AtomicLong atomicCounter = new java.util.concurrent.atomic.AtomicLong();
        Thread[] atomicThreads = new Thread[4];
        for (int t = 0; t < atomicThreads.length; t++) {
            atomicThreads[t] = new Thread(() -> {
                for (int i = 0; i < 100_000; i++) {
                    atomicCounter.incrementAndGet(); // CAS - no lock needed
                }
            }, "atomic-" + t);
        }

        long atomicStart = System.nanoTime();
        for (Thread t : atomicThreads) t.start();
        for (Thread t : atomicThreads) t.join();
        long atomicTime = System.nanoTime() - atomicStart;

        System.out.printf("Lock-free AtomicLong:   %,d ms, total count=%d%n",
                atomicTime / 1_000_000, atomicCounter.get());
        System.out.printf("Speedup: ~%.1fx%n",
                contentedTime > 0 ? (double) contentedTime / atomicTime : 1.0);
        System.out.println("NOTE: LongAdder is even faster for high-contention increment-only scenarios.");
    }
}
