package com.deepdive.month01.week01;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Week 1: JVM Memory Management
 * Topics: Garbage Collection, Heap Space, Young vs Old Generations
 *
 * Key concepts:
 * - Young Generation (Eden + Survivor spaces): short-lived objects
 * - Old Generation (Tenured): long-lived objects
 * - Metaspace: class metadata (replaced PermGen in Java 8+)
 * - GC Types: Serial, Parallel, G1, ZGC, Shenandoah
 *
 * Run with JVM flags to observe GC:
 *   -Xms256m -Xmx512m -XX:+UseG1GC -verbose:gc -XX:+PrintGCDetails
 *   -Xlog:gc*:file=gc.log:time,uptime,level,tags  (Java 9+ unified logging)
 *
 * GC Algorithm comparison:
 * - Serial GC     (-XX:+UseSerialGC):     single-threaded, for small heaps / client apps
 * - Parallel GC   (-XX:+UseParallelGC):   multi-threaded, throughput-focused, default until Java 8
 * - G1 GC         (-XX:+UseG1GC):         balanced latency+throughput, default since Java 9
 * - ZGC           (-XX:+UseZGC):          sub-millisecond pauses, Java 15+ production-ready
 * - Shenandoah    (-XX:+UseShenandoahGC): concurrent compaction, RedHat-originated
 */
public class GarbageCollectionDemo {

    // CONCEPT: Static fields are GC roots - they are never collected while the class is loaded.
    // This is a common source of memory leaks in long-running applications.
    private static final List<byte[]> LONG_LIVED_OBJECTS = new ArrayList<>();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Garbage Collection Deep Dive ===");
        System.out.println("JVM: " + System.getProperty("java.vm.name"));
        System.out.println("Java: " + System.getProperty("java.version"));

        demonstrateHeapGenerations();
        demonstrateReferenceTypes();
        demonstrateGcPressure();
        printMemoryStats("Final state");
    }

    /**
     * CONCEPT: The JVM heap is divided into generations based on object age.
     * Most objects die young (the "generational hypothesis"), so GC focuses on Young Gen first.
     *
     * Young Generation:
     *   - Eden space: new objects allocated here
     *   - Survivor 0 / Survivor 1: objects surviving minor GC are copied between these
     *   - After enough minor GCs, objects are "promoted" to Old Gen
     *
     * Old (Tenured) Generation:
     *   - Long-lived objects that survived many minor GCs
     *   - Major GC (Full GC) is expensive - minimize object promotion
     */
    private static void demonstrateHeapGenerations() throws InterruptedException {
        System.out.println("\n--- Heap Generations ---");
        printMemoryStats("Before allocation");

        // WHY: Allocating small, short-lived byte arrays simulates Eden space pressure.
        // These objects should be collected in the next minor GC.
        System.out.println("Allocating short-lived objects (Eden space pressure)...");
        for (int i = 0; i < 1000; i++) {
            byte[] shortLived = new byte[10_000]; // 10 KB each, ~10 MB total
            // NOTE: shortLived goes out of scope at end of loop iteration -> eligible for GC
            if (i % 100 == 0) {
                // Force GC hint (JVM may ignore this - it's a hint, not a command)
                System.gc();
                printMemoryStats("  After " + i + " allocations");
            }
        }

        // WHY: Keeping a reference in a static list moves objects to Old Gen territory.
        // This simulates a cache or session store that grows over time.
        System.out.println("\nAllocating long-lived objects (promoting to Old Gen)...");
        for (int i = 0; i < 10; i++) {
            LONG_LIVED_OBJECTS.add(new byte[1_000_000]); // 1 MB each = 10 MB total retained
        }
        printMemoryStats("After long-lived allocations");
    }

    /**
     * CONCEPT: Java has 4 reference strength levels, allowing the GC to collect
     * objects selectively based on memory pressure.
     *
     * Strong reference:  Object obj = new Object()   -> Never collected while reachable
     * Soft reference:    SoftReference<T>             -> Collected when JVM needs memory (good for caches)
     * Weak reference:    WeakReference<T>             -> Collected in next GC cycle (good for canonicalization)
     * Phantom reference: PhantomReference<T>          -> After finalization, for cleanup actions
     */
    private static void demonstrateReferenceTypes() throws InterruptedException {
        System.out.println("\n--- Reference Types ---");

        // CONCEPT: SoftReference - ideal for memory-sensitive caches.
        // The JVM guarantees soft refs are cleared before OutOfMemoryError.
        var data = new byte[500_000]; // 500 KB
        SoftReference<byte[]> softRef = new SoftReference<>(data);
        data = null; // Remove strong reference
        System.out.println("SoftReference get() before GC: " + (softRef.get() != null ? "alive" : "null"));
        System.gc();
        Thread.sleep(100);
        // NOTE: Under normal memory conditions, soft ref will likely still be alive
        System.out.println("SoftReference get() after GC: " + (softRef.get() != null ? "alive" : "null"));

        // CONCEPT: WeakReference - collected aggressively. Used in WeakHashMap
        // (e.g., ThreadLocal internals, event listener registries to avoid leaks).
        var weakData = new byte[100_000];
        WeakReference<byte[]> weakRef = new WeakReference<>(weakData);
        weakData = null;
        System.out.println("WeakReference get() before GC: " + (weakRef.get() != null ? "alive" : "null"));
        System.gc();
        Thread.sleep(100);
        System.out.println("WeakReference get() after GC: " + (weakRef.get() != null ? "alive (possible)" : "collected"));

        // CONCEPT: PhantomReference - used for post-finalization cleanup.
        // You cannot get the referent (get() always returns null).
        // Java 9+ Cleaner API uses this internally.
        ReferenceQueue<byte[]> refQueue = new ReferenceQueue<>();
        var phantomData = new byte[100_000];
        PhantomReference<byte[]> phantomRef = new PhantomReference<>(phantomData, refQueue);
        phantomData = null;
        System.gc();
        Thread.sleep(100);
        // NOTE: phantomRef.get() always returns null - this is by design
        System.out.println("PhantomReference get() always returns: " + phantomRef.get());
        System.out.println("PhantomReference enqueued: " + (refQueue.poll() != null ? "yes (ready for cleanup)" : "not yet"));
    }

    /**
     * CONCEPT: GC pressure happens when objects are allocated faster than they are collected.
     * This leads to frequent minor GCs, and eventually Full GCs which "stop the world".
     *
     * Staff Engineer tip: Profile your application with JFR (Java Flight Recorder)
     * to understand allocation rates before optimizing.
     *   jcmd <pid> JFR.start duration=60s filename=profile.jfr
     *   jcmd <pid> JFR.dump
     */
    private static void demonstrateGcPressure() {
        System.out.println("\n--- GC Pressure Simulation ---");

        long startTime = System.currentTimeMillis();
        long gcsBefore = getGcCount();

        // WHY: String concatenation in a loop creates many short-lived String objects.
        // This is a common beginner mistake - use StringBuilder instead.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            // BAD: String str = "prefix" + i + "suffix"; // Creates temporary objects
            sb.append("prefix").append(i).append("suffix\n"); // GOOD: reuses buffer
        }

        long gcsAfter = getGcCount();
        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("StringBuilder result length: " + sb.length() + " chars");
        System.out.println("Elapsed: " + elapsed + "ms, GC count change: " + (gcsAfter - gcsBefore));
    }

    private static long getGcCount() {
        // WHY: ManagementFactory gives programmatic access to JVM internals
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                .stream()
                .mapToLong(gc -> gc.getCollectionCount() < 0 ? 0 : gc.getCollectionCount())
                .sum();
    }

    private static void printMemoryStats(String label) {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        System.out.printf("[%s] Used: %d MB / Total: %d MB / Max: %d MB%n",
                label,
                usedMemory / 1_048_576,
                totalMemory / 1_048_576,
                maxMemory / 1_048_576);
    }
}