package com.deepdive.month01.week01;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Week 1: Heap Memory Analysis
 *
 * CONCEPT: Understanding heap memory pools allows you to tune the JVM precisely.
 * The JVM exposes memory statistics via MXBeans (Management Extensions Beans).
 *
 * Key memory regions:
 * - Heap:       Object storage (Young Gen + Old Gen)
 * - Non-Heap:   Metaspace (class metadata), Code Cache (JIT compiled code), etc.
 * - Stack:      Per-thread, not on heap. Fixed or growable size (-Xss)
 * - Direct:     Off-heap NIO buffers (ByteBuffer.allocateDirect)
 *
 * Useful JVM flags for memory analysis:
 *   -Xms512m             : Initial heap size
 *   -Xmx2g               : Maximum heap size
 *   -XX:MetaspaceSize=256m     : Initial Metaspace
 *   -XX:MaxMetaspaceSize=512m  : Cap Metaspace growth
 *   -XX:NewRatio=3       : Old Gen / Young Gen ratio (3 means Old is 3x Young)
 *   -XX:SurvivorRatio=8  : Eden / Survivor ratio
 */
public class HeapMemoryAnalysis {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Heap Memory Analysis ===");

        analyzeMemoryPools();
        demonstrateWeakHashMap();
        demonstrateSoftReferenceCache();
        trackAllocationWithManagementBeans();
    }

    /**
     * CONCEPT: MemoryPoolMXBeans represent individual memory regions.
     * Monitoring these pools helps diagnose OOM errors and GC thrashing.
     *
     * WHY: In production, export these metrics to Prometheus/Grafana using
     * Micrometer or the JMX exporter for continuous monitoring.
     */
    private static void analyzeMemoryPools() {
        System.out.println("\n--- JVM Memory Pools ---");

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

        // Heap memory (controlled by -Xms and -Xmx)
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        System.out.println("Heap Memory:");
        printMemoryUsage("  ", heapUsage);

        // Non-heap: Metaspace, Code Cache, Compressed Class Space
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        System.out.println("Non-Heap Memory (Metaspace, Code Cache):");
        printMemoryUsage("  ", nonHeapUsage);

        // Individual pools provide finer-grained visibility
        System.out.println("\nIndividual Memory Pools:");
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean pool : pools) {
            System.out.printf("  Pool: %-40s Type: %s%n", pool.getName(), pool.getType());
            if (pool.isUsageThresholdSupported()) {
                printMemoryUsage("    Usage: ", pool.getUsage());
                // NOTE: Peak usage shows the maximum observed since JVM start or last reset
                if (pool.getPeakUsage() != null) {
                    printMemoryUsage("    Peak:  ", pool.getPeakUsage());
                }
            }
        }
    }

    private static void printMemoryUsage(String prefix, MemoryUsage usage) {
        System.out.printf("%sInit: %d MB, Used: %d MB, Committed: %d MB, Max: %d MB%n",
                prefix,
                mbOrNeg(usage.getInit()),
                mbOrNeg(usage.getUsed()),
                mbOrNeg(usage.getCommitted()),
                mbOrNeg(usage.getMax()));
    }

    private static long mbOrNeg(long bytes) {
        return bytes < 0 ? -1 : bytes / 1_048_576;
    }

    /**
     * CONCEPT: WeakHashMap is a map where keys are weakly referenced.
     * When a key has no other strong references, the entry is automatically removed.
     *
     * WHY: This is ideal for caches where the cached object's lifecycle
     * should follow the lifecycle of the key (e.g., per-object metadata caches).
     *
     * CAUTION: String literals are interned and are always strongly reachable
     * (class loader holds them). Use 'new String(...)' to create a non-interned key.
     */
    private static void demonstrateWeakHashMap() throws InterruptedException {
        System.out.println("\n--- WeakHashMap Demo ---");

        // WHY: WeakHashMap is used internally by ThreadLocal and some caching frameworks
        WeakHashMap<Object, String> cache = new WeakHashMap<>();

        Object key1 = new Object(); // Strong reference
        Object key2 = new Object(); // Strong reference
        cache.put(key1, "value-for-key1");
        cache.put(key2, "value-for-key2");

        System.out.println("Cache size before nulling key1: " + cache.size());

        key1 = null; // Remove strong reference to key1
        System.gc();
        Thread.sleep(200); // Give GC time to run

        // NOTE: WeakHashMap entries are cleaned lazily during map operations
        // The size may still show 2 until a map operation triggers cleanup
        System.out.println("Cache size after GC (may still show 2 until next map operation): " + cache.size());
        // Trigger cleanup by performing a get
        cache.get(key2); // This triggers expunge of stale entries
        System.out.println("Cache size after get() triggers cleanup: " + cache.size());
        System.out.println("key2 entry still present: " + cache.containsKey(key2));
    }

    /**
     * CONCEPT: SoftReference-based cache - objects are held until memory is needed.
     * This pattern is used by Guava's CachBuilder (softValues()) and Ehcache.
     *
     * WHY: Unlike strong caches that can cause OOM, soft caches self-regulate.
     * The JVM will clear soft references before throwing OutOfMemoryError.
     *
     * NOTE: The JVM uses a time-based policy for clearing soft refs:
     *   -XX:SoftRefLRUPolicyMSPerMB (default 1000ms per MB of free heap)
     */
    private static void demonstrateSoftReferenceCache() throws InterruptedException {
        System.out.println("\n--- SoftReference Cache Pattern ---");

        // Simple soft-reference cache implementation
        Map<String, SoftReference<byte[]>> softCache = new java.util.HashMap<>();

        // Populate cache with 5 MB of data
        for (int i = 0; i < 5; i++) {
            String key = "expensive-computation-" + i;
            byte[] expensiveResult = new byte[1_000_000]; // 1 MB
            expensiveResult[0] = (byte) i; // mark with content
            softCache.put(key, new SoftReference<>(expensiveResult));
        }

        System.out.println("Soft cache entries: " + softCache.size());

        // Simulate cache lookup with recompute-on-miss
        String lookupKey = "expensive-computation-2";
        SoftReference<byte[]> ref = softCache.get(lookupKey);
        byte[] result = (ref != null) ? ref.get() : null;

        if (result != null) {
            System.out.println("Cache HIT for '" + lookupKey + "', first byte: " + result[0]);
        } else {
            // CONCEPT: This branch handles GC-caused cache eviction transparently
            System.out.println("Cache MISS for '" + lookupKey + "' (GC collected it) - recomputing...");
            result = new byte[1_000_000]; // recompute
            softCache.put(lookupKey, new SoftReference<>(result));
        }

        // WHY: Check all entries for GC'd references and clean up (avoid memory leak in the HashMap itself)
        softCache.entrySet().removeIf(e -> e.getValue().get() == null);
        System.out.println("Soft cache after cleanup: " + softCache.size() + " entries");
    }

    /**
     * CONCEPT: Track allocation rates using GcInfo from GarbageCollectorMXBeans.
     * This is how APM tools (Datadog, New Relic) calculate GC overhead.
     *
     * Staff Engineer tip: GC pause time > 200ms usually indicates a tuning problem.
     * Target: GC overhead < 5% of total CPU time.
     */
    private static void trackAllocationWithManagementBeans() {
        System.out.println("\n--- GC Statistics from MXBeans ---");

        ManagementFactory.getGarbageCollectorMXBeans().forEach(gcBean -> {
            System.out.printf("  GC Collector: %-30s | Collections: %3d | Total time: %d ms%n",
                    gcBean.getName(),
                    gcBean.getCollectionCount() < 0 ? 0 : gcBean.getCollectionCount(),
                    gcBean.getCollectionTime() < 0 ? 0 : gcBean.getCollectionTime());
        });

        // CONCEPT: Thread MXBean shows memory per thread (useful for leak detection)
        var threadMxBean = ManagementFactory.getThreadMXBean();
        if (threadMxBean.isThreadAllocatedMemorySupported()) {
            threadMxBean.setThreadAllocatedMemoryEnabled(true);
            long allocated = threadMxBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            System.out.printf("%n  Current thread allocated bytes: %,d bytes (~%d MB)%n",
                    allocated, allocated / 1_048_576);
        } else {
            System.out.println("  Thread allocated memory tracking not supported on this JVM.");
        }

        // NOTE: Java 21 Virtual Threads (Project Loom) use much less memory than platform threads
        // Virtual thread stack is dynamically sized (starts at ~1KB vs 512KB for platform threads)
        System.out.println("\n  Virtual Thread memory note:");
        System.out.println("  Platform thread stack: ~512KB each (configured via -Xss)");
        System.out.println("  Virtual thread stack:  ~1KB-8KB (grows dynamically on heap)");
        System.out.println("  This allows millions of concurrent virtual threads with limited memory");
    }
}
