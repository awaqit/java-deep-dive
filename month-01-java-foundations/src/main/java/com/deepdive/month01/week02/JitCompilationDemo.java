package com.deepdive.month01.week02;

/**
 * Week 2: JVM Performance Tuning - JIT Compilation
 *
 * CONCEPT: The JIT (Just-In-Time) compiler converts bytecode to native machine code
 * at runtime. It has two tiers:
 *
 * - C1 (Client Compiler): Fast compilation, limited optimization. Used for code that
 *   runs occasionally. Produces code quickly at startup.
 *
 * - C2 (Server Compiler): Slow compilation, aggressive optimization. Used for hot methods.
 *   Produces highly optimized native code after profiling.
 *
 * Java 21 uses Tiered Compilation (default since Java 7u4):
 *   Level 0: Interpreter
 *   Level 1: C1 without profiling
 *   Level 2: C1 with limited profiling
 *   Level 3: C1 with full profiling
 *   Level 4: C2 (fully optimized)
 *
 * Key JIT optimizations:
 * - Method inlining:          Small methods are inlined (eliminates call overhead)
 * - Loop unrolling:           Duplicate loop body to reduce branch instructions
 * - Dead code elimination:    Remove code whose result is never used
 * - Escape analysis:          Stack-allocate objects that don't escape their scope
 * - Vectorization (SIMD):     Use CPU vector instructions for array operations
 * - Lock coarsening/elision:  Merge or remove unnecessary synchronization
 *
 * JVM flags for JIT inspection:
 *   -XX:+PrintCompilation                  : Print when methods are JIT compiled
 *   -XX:+UnlockDiagnosticVMOptions
 *   -XX:+PrintInlining                     : Show inlining decisions
 *   -XX:CompileThreshold=1000              : Compile after N invocations (default: 10000)
 *   -XX:+TieredCompilation                 : Enable tiered (default: on)
 *   -Xint                                  : Interpreter-only mode (for comparison)
 */
public class JitCompilationDemo {

    // CONCEPT: Loop count for "warm-up" - JIT compiles after ~10000 invocations by default
    private static final int WARMUP_ITERATIONS = 50_000;
    private static final int BENCHMARK_ITERATIONS = 1_000_000;

    public static void main(String[] args) {
        System.out.println("=== JIT Compilation Demo ===");
        System.out.println("JVM: " + System.getProperty("java.vm.name"));
        System.out.println("Warmup iterations: " + WARMUP_ITERATIONS);

        demonstrateWarmupEffect();
        demonstrateMethodInlining();
        demonstrateEscapeAnalysis();
        demonstrateDeadCodeElimination();
    }

    /**
     * CONCEPT: JIT warm-up effect - first invocations are slow (interpreted),
     * subsequent invocations accelerate as JIT kicks in.
     *
     * WHY: This is why microservices in Kubernetes often have slow startup metrics.
     * Solutions: GraalVM Native Image (AOT), JVM class data sharing (CDS),
     * AppCDS (Application Class Data Sharing), Project Leyden (future).
     */
    private static void demonstrateWarmupEffect() {
        System.out.println("\n--- JIT Warm-up Effect ---");

        // PHASE 1: Cold (interpreted)
        long coldStart = System.nanoTime();
        long coldSum = 0;
        for (int i = 0; i < 1000; i++) {
            coldSum += computeIntensive(i);
        }
        long coldTime = System.nanoTime() - coldStart;
        System.out.printf("Cold (first 1000):   %,d ns (sum=%d)%n", coldTime, coldSum);

        // PHASE 2: Warming up (C1 compiler kicks in)
        long warmStart = System.nanoTime();
        long warmSum = 0;
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            warmSum += computeIntensive(i % 100);
        }
        long warmTime = System.nanoTime() - warmStart;
        System.out.printf("Warming (%,d):  %,d ns (sum=%d)%n", WARMUP_ITERATIONS, warmTime, warmSum);

        // PHASE 3: Hot (C2 fully optimized)
        long hotStart = System.nanoTime();
        long hotSum = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            hotSum += computeIntensive(i % 100);
        }
        long hotTime = System.nanoTime() - hotStart;
        System.out.printf("Hot (%,d): %,d ns (sum=%d)%n", BENCHMARK_ITERATIONS, hotTime, hotSum);

        if (coldTime > 0) {
            System.out.printf("Speedup ratio (cold 1000 ops vs hot 1000 ops): ~%.1fx%n",
                    (double) coldTime / (hotTime / 1000.0));
        }
    }

    // CONCEPT: This method will be JIT-compiled after ~10000 invocations.
    // Keep it small enough to be inlined by C2 (< ~35 bytecodes).
    private static long computeIntensive(long n) {
        // Fibonacci approximation - computationally meaningful but predictable
        long a = n, b = n + 1;
        for (int i = 0; i < 10; i++) {
            long temp = a + b;
            a = b;
            b = temp;
        }
        return b;
    }

    /**
     * CONCEPT: Method inlining - the JIT replaces a method call with the method body.
     * This eliminates the call overhead (stack frame setup, etc.) and enables
     * further optimizations like constant folding.
     *
     * The JVM inlines methods that are:
     * - Small (< ~35 bytecodes by default, -XX:MaxInlineSize)
     * - Frequently called (hot)
     * - Not overridden (monomorphic dispatch)
     *
     * NOTE: Final methods, private methods, and static methods are easier to inline
     * because the JVM doesn't need to check for overrides.
     */
    private static void demonstrateMethodInlining() {
        System.out.println("\n--- Method Inlining ---");

        // Small method - JIT will inline this (eliminating the call overhead)
        long sum = 0;
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            sum += addTwo(i, i + 1); // Will likely be inlined to: sum += i + (i+1)
        }
        long inlinedTime = System.nanoTime() - start;
        System.out.printf("Inlined small method:  %,d ns, sum=%d%n", inlinedTime, sum);

        // Megamorphic call site - harder to inline due to multiple implementations
        // JIT uses inline caches; if too many types, it falls back to vtable dispatch
        Transformer[] transformers = {
            new DoubleTransformer(),
            new NegateTransformer(),
            new SquareTransformer()
        };
        long polySum = 0;
        long polyStart = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            // CONCEPT: Polymorphic dispatch - JIT must handle multiple implementations
            polySum += transformers[i % 3].transform(i);
        }
        long polyTime = System.nanoTime() - polyStart;
        System.out.printf("Polymorphic dispatch:  %,d ns, sum=%d%n", polyTime, polySum);
        System.out.println("NOTE: Polymorphic calls are harder for JIT to optimize.");
        System.out.println("      Sealed classes (Java 17+) help JIT with exhaustive type checking.");
    }

    // NOTE: Private final methods are prime candidates for inlining
    private static long addTwo(long a, long b) {
        return a + b;
    }

    interface Transformer {
        long transform(long value);
    }

    static final class DoubleTransformer implements Transformer {
        @Override public long transform(long value) { return value * 2; }
    }

    static final class NegateTransformer implements Transformer {
        @Override public long transform(long value) { return -value; }
    }

    static final class SquareTransformer implements Transformer {
        @Override public long transform(long value) { return value * value; }
    }

    /**
     * CONCEPT: Escape Analysis - the JIT determines whether an object "escapes" its
     * creating method (i.e., is returned or stored in a field accessible elsewhere).
     *
     * If an object does NOT escape:
     * - It can be allocated on the stack (no GC pressure!)
     * - Synchronization can be eliminated (lock elision)
     * - Scalar replacement: object fields become local variables
     *
     * WHY: This is why creating small Point/Rectangle objects in tight loops
     * may be optimized away entirely by C2.
     *
     * To see escape analysis decisions:
     *   -XX:+UnlockDiagnosticVMOptions -XX:+PrintEscapeAnalysis
     */
    private static void demonstrateEscapeAnalysis() {
        System.out.println("\n--- Escape Analysis ---");

        // CONCEPT: Point is a record (Java 16+). Since it doesn't escape the loop body,
        // C2 may eliminate heap allocation entirely (scalar replacement).
        record Point(double x, double y) {
            double distance() {
                return Math.sqrt(x * x + y * y);
            }
        }

        double totalDistance = 0;
        long start = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            // WHY: With escape analysis, this Point allocation may be eliminated.
            // The JIT can see that 'p' never escapes this loop body.
            Point p = new Point(i * 0.001, i * 0.002);
            totalDistance += p.distance(); // Result escapes (sum does), but not the object
        }

        long elapsed = System.nanoTime() - start;
        System.out.printf("Point distance sum: %.2f%n", totalDistance);
        System.out.printf("Time with potential stack allocation: %,d ns%n", elapsed);
        System.out.println("Run with -XX:-DoEscapeAnalysis to see the difference (likely slower).");
    }

    /**
     * CONCEPT: Dead code elimination - the JIT removes code paths that are provably
     * unreachable or produce results that are never used.
     *
     * WHY: This is why microbenchmarks in Java are tricky! The JIT may eliminate
     * your entire benchmark if results are not consumed.
     * Always use JMH (Java Microbenchmark Harness) for serious benchmarking.
     */
    private static void demonstrateDeadCodeElimination() {
        System.out.println("\n--- Dead Code Elimination ---");

        // BAD benchmark (result thrown away - JIT may eliminate the entire loop!)
        long badStart = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            @SuppressWarnings("unused")
            long result = computeIntensive(i); // result is unused - JIT may remove this!
        }
        long badTime = System.nanoTime() - badStart;
        System.out.printf("BAD benchmark (discarded result): %,d ns - may be unreliable!%n", badTime);

        // GOOD benchmark (result is consumed via accumulation)
        long sum = 0;
        long goodStart = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            sum += computeIntensive(i); // sum is returned/used, preventing elimination
        }
        long goodTime = System.nanoTime() - goodStart;
        System.out.printf("GOOD benchmark (result consumed): %,d ns, sum=%d%n", goodTime, sum);

        System.out.println("\nNOTE: For accurate benchmarks, use JMH:");
        System.out.println("  @Benchmark public long myBenchmark() { return computeIntensive(42); }");
        System.out.println("  JMH handles: warmup, dead code prevention, JVM forking, statistics");
    }
}
