package com.deepdive.month01.week01;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Week 1: Memory Leak Patterns and Fixes
 *
 * CONCEPT: A memory leak in Java is not a dangling pointer (like in C/C++),
 * but rather an object that is no longer needed but still referenced,
 * preventing the GC from collecting it.
 *
 * Common leak patterns:
 * 1. Static collections that grow unbounded
 * 2. Listener/callback registrations that are never removed
 * 3. ThreadLocal variables not removed after request completion
 * 4. Inner class holding implicit reference to outer class
 * 5. ClassLoader leaks (common in application servers)
 * 6. Finalizers preventing timely GC
 *
 * Tools to detect memory leaks:
 * - Java Flight Recorder (JFR): jcmd <pid> JFR.start
 * - Heap dump: jmap -dump:format=b,file=heap.hprof <pid>
 * - Eclipse MAT, VisualVM, IntelliJ Profiler to analyze heap dumps
 * - YourKit, Async Profiler for allocation profiling
 */
public class MemoryLeakExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Memory Leak Patterns ===");
        System.out.println("WARNING: Some examples intentionally leak memory - observe with profiler");

        demonstrateStaticCollectionLeak();
        demonstrateFixedStaticCollection();
        demonstrateListenerLeak();
        demonstrateThreadLocalLeak();
        demonstrateInnerClassLeak();
    }

    // ============================================================
    // PATTERN 1: Unbounded static collection (very common in caches)
    // ============================================================

    // BAD: This Map grows forever - a textbook memory leak
    private static final Map<String, byte[]> LEAKING_CACHE = new HashMap<>();

    private static void demonstrateStaticCollectionLeak() {
        System.out.println("\n--- Pattern 1: Static Collection Leak (BAD) ---");

        // WHY: Each simulated request adds to the static map but never removes entries.
        // In a real service handling millions of requests, this will eventually OOM.
        for (int i = 0; i < 100; i++) {
            String sessionId = "session-" + i;
            byte[] sessionData = new byte[10_000]; // 10 KB per session
            LEAKING_CACHE.put(sessionId, sessionData);
        }
        System.out.println("LEAKING_CACHE size (grows forever): " + LEAKING_CACHE.size());
        System.out.println("Memory consumed: ~" + (LEAKING_CACHE.size() * 10) + " KB");
    }

    // FIXED: Bounded cache with eviction policy
    // WHY: Use a LinkedHashMap with access-order eviction, or Guava Cache, or Caffeine
    private static final int MAX_CACHE_SIZE = 50;
    private static final Map<String, byte[]> BOUNDED_CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            // CONCEPT: Access-order LRU eviction - remove oldest accessed when full
            return size() > MAX_CACHE_SIZE;
        }
    };

    private static void demonstrateFixedStaticCollection() {
        System.out.println("\n--- Pattern 1: Bounded LRU Cache (FIXED) ---");

        for (int i = 0; i < 100; i++) {
            String sessionId = "session-" + i;
            byte[] sessionData = new byte[10_000];
            BOUNDED_CACHE.put(sessionId, sessionData);
        }
        // NOTE: Size capped at MAX_CACHE_SIZE
        System.out.println("BOUNDED_CACHE size (capped at " + MAX_CACHE_SIZE + "): " + BOUNDED_CACHE.size());
    }

    // ============================================================
    // PATTERN 2: Listener registration without deregistration
    // ============================================================

    interface EventListener {
        void onEvent(String event);
    }

    static class EventBus {
        private final List<EventListener> listeners = new ArrayList<>();

        public void addListener(EventListener listener) {
            listeners.add(listener);
        }

        // BAD: No removeListener method - listeners accumulate forever
        // This is especially bad when listeners are anonymous/lambda closures
        // that capture large objects

        public void removeListener(EventListener listener) {
            listeners.remove(listener);
        }

        public void publish(String event) {
            listeners.forEach(l -> l.onEvent(event));
        }

        public int listenerCount() {
            return listeners.size();
        }
    }

    static class Service {
        private final String name;
        private final byte[] data; // Simulates held resources (could be connections, buffers)

        Service(String name) {
            this.name = name;
            this.data = new byte[100_000]; // 100 KB
        }

        EventListener createListener() {
            // CONCEPT: Lambda captures 'this' - the EventBus holds the listener,
            // which holds the lambda, which holds the Service. The Service cannot be GC'd
            // even after it's "done" if this listener is never removed.
            return event -> System.out.println(name + " received: " + event);
        }
    }

    private static void demonstrateListenerLeak() {
        System.out.println("\n--- Pattern 2: Listener Leak ---");
        EventBus bus = new EventBus();

        // BAD: Listeners added but never removed
        for (int i = 0; i < 10; i++) {
            Service svc = new Service("Service-" + i);
            bus.addListener(svc.createListener()); // svc cannot be GC'd!
            // svc goes "out of scope" here but is still referenced via listener
        }
        System.out.println("BAD: Listener count (leaking): " + bus.listenerCount());

        // FIXED: Always remove listeners when done
        EventBus fixedBus = new EventBus();
        List<EventListener> registeredListeners = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Service svc = new Service("Service-" + i);
            EventListener listener = svc.createListener();
            fixedBus.addListener(listener);
            registeredListeners.add(listener); // keep track for removal
        }
        // Cleanup when services are done (e.g., on shutdown or when component is disposed)
        registeredListeners.forEach(fixedBus::removeListener);
        System.out.println("FIXED: Listener count after cleanup: " + fixedBus.listenerCount());
    }

    // ============================================================
    // PATTERN 3: ThreadLocal leak (critical in thread pool environments)
    // ============================================================

    // WHY: ThreadLocal is very useful for per-request context (request ID, security context).
    // BUT: In thread pools (Spring, Tomcat), threads are reused. If ThreadLocal is not
    // cleared, values from one request "bleed" into the next request on the same thread.
    // Worse, it prevents GC of objects referenced by the ThreadLocal value.
    private static final ThreadLocal<byte[]> REQUEST_CONTEXT = new ThreadLocal<>();

    private static void demonstrateThreadLocalLeak() throws InterruptedException {
        System.out.println("\n--- Pattern 3: ThreadLocal Leak ---");

        // Simulate request handling in a thread pool
        Thread simulatedPoolThread = new Thread(() -> {
            // Request 1 sets ThreadLocal (simulating a filter/interceptor)
            REQUEST_CONTEXT.set(new byte[500_000]); // 500 KB context
            System.out.println("Request 1: set ThreadLocal, size = 500 KB");

            // BAD: Request 1 finishes without cleaning up
            // REQUEST_CONTEXT.remove(); // <-- THIS WOULD BE THE FIX

            // Request 2 runs on the same thread (thread pool reuse!)
            byte[] leaked = REQUEST_CONTEXT.get();
            if (leaked != null) {
                System.out.println("Request 2: ThreadLocal still has old value! (" + leaked.length + " bytes leaked)");
            }
        });

        simulatedPoolThread.start();
        simulatedPoolThread.join();

        // FIXED: Always use try-finally to clean up ThreadLocal
        Thread fixedPoolThread = new Thread(() -> {
            try {
                REQUEST_CONTEXT.set(new byte[500_000]);
                System.out.println("FIXED Request 1: set ThreadLocal");
                // ... do request processing ...
            } finally {
                REQUEST_CONTEXT.remove(); // CRITICAL: always remove in finally block
                System.out.println("FIXED Request 1: cleaned up ThreadLocal");
            }

            byte[] afterCleanup = REQUEST_CONTEXT.get();
            System.out.println("FIXED Request 2: ThreadLocal is " + (afterCleanup == null ? "null (clean)" : "still set!"));
        });

        fixedPoolThread.start();
        fixedPoolThread.join();
    }

    // ============================================================
    // PATTERN 4: Non-static inner class holding outer class reference
    // ============================================================

    static class OuterClass {
        private final byte[] bigData = new byte[1_000_000]; // 1 MB

        // BAD: Non-static inner class implicitly holds a reference to OuterClass.
        // If InnerClass instance escapes (e.g., passed to thread pool), OuterClass cannot be GC'd.
        class LeakingInnerClass implements Runnable {
            @Override
            public void run() {
                // Implicit reference: OuterClass.this.bigData is accessible
                System.out.println("LeakingInnerClass: outer.bigData length = " + bigData.length);
            }
        }

        // FIXED: Static nested class has NO implicit reference to outer class
        static class SafeInnerClass implements Runnable {
            private final int dataLength; // Only copies what it needs

            SafeInnerClass(int dataLength) {
                this.dataLength = dataLength;
            }

            @Override
            public void run() {
                System.out.println("SafeInnerClass: dataLength = " + dataLength);
            }
        }
    }

    private static void demonstrateInnerClassLeak() {
        System.out.println("\n--- Pattern 4: Inner Class Implicit Reference ---");

        OuterClass outer = new OuterClass();

        // BAD: leakingTask holds reference to outer -> outer.bigData cannot be collected
        Runnable leakingTask = outer.new LeakingInnerClass();
        leakingTask.run();
        // If leakingTask is submitted to an ExecutorService, outer is held alive!

        // FIXED: static inner class only holds what it needs
        Runnable safeTask = new OuterClass.SafeInnerClass(outer.bigData.length);
        outer = null; // outer CAN be collected now - safeTask doesn't hold a reference
        System.gc();
        safeTask.run();

        System.out.println("\nKey Rule: Prefer static nested classes over inner classes.");
        System.out.println("         Use lambdas carefully - they capture 'this' in instance contexts.");
    }
}
