package com.deepdive.month01.week03;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Week 3: Atomic Variables and Lock-Free Concurrency
 *
 * CONCEPT: Atomic variables provide thread-safe operations without locks.
 * They are implemented using CPU-level Compare-And-Swap (CAS) instructions.
 *
 * CAS (Compare-And-Swap):
 *   atomic { if (value == expected) { value = newValue; return true; } return false; }
 *
 * This is a hardware primitive (CMPXCHG on x86, LDXR/STXR on ARM).
 * It's cheaper than a mutex because:
 * - No OS kernel involvement (no context switch)
 * - No thread blocking
 * - Optimistic concurrency: assume no contention, retry on failure
 *
 * Available atomic classes:
 * - AtomicInteger, AtomicLong, AtomicBoolean:     Simple value types
 * - AtomicReference<V>:                           Reference to any object
 * - AtomicIntegerArray, AtomicLongArray:           Array with atomic operations
 * - AtomicIntegerFieldUpdater, AtomicLongFieldUpdater: Update volatile fields atomically
 * - LongAdder, LongAccumulator:                   High-contention counters (Java 8+)
 * - VarHandle (Java 9+):                          Low-level atomic access to fields
 *
 * ABA Problem:
 * Thread 1 reads A, Thread 2 changes A->B->A, Thread 1's CAS succeeds (wrongly).
 * Solution: AtomicStampedReference (version counter) or AtomicMarkableReference.
 */
public class AtomicVariablesDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Atomic Variables & CAS Demo ===");

        demonstrateAtomicInteger();
        demonstrateAtomicReference();
        demonstrateLongAdderVsAtomicLong();
        demonstrateCompareAndSwap();
        demonstrateAtomicABAIssue();
        demonstrateNonBlockingStack();
    }

    /**
     * CONCEPT: AtomicInteger for lock-free counters and state machines.
     * All operations are performed as single atomic hardware instructions.
     */
    private static void demonstrateAtomicInteger() throws InterruptedException {
        System.out.println("\n--- AtomicInteger ---");

        AtomicInteger counter = new AtomicInteger(0);
        int numThreads = 10;
        int incrementsPerThread = 10_000;

        CountDownLatch latch = new CountDownLatch(numThreads);
        Thread[] threads = new Thread[numThreads];

        for (int t = 0; t < numThreads; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < incrementsPerThread; i++) {
                    counter.incrementAndGet(); // CAS-based, no lock needed
                }
                latch.countDown();
            });
        }

        for (Thread t : threads) t.start();
        latch.await();

        int expected = numThreads * incrementsPerThread;
        System.out.printf("Expected: %d, Actual: %d, Correct: %s%n",
                expected, counter.get(), expected == counter.get() ? "YES" : "NO (data race!)");

        // Useful atomic operations
        System.out.println("\nAtomicInteger operations:");
        AtomicInteger ai = new AtomicInteger(10);
        System.out.println("  getAndSet(20):          " + ai.getAndSet(20) + " (was: 10, now: " + ai.get() + ")");
        System.out.println("  compareAndSet(20, 30):  " + ai.compareAndSet(20, 30) + " (now: " + ai.get() + ")");
        System.out.println("  compareAndSet(20, 40):  " + ai.compareAndSet(20, 40) + " (still: " + ai.get() + ")");
        System.out.println("  getAndUpdate(x->x*2):   " + ai.getAndUpdate(x -> x * 2) + " (was: 30, now: " + ai.get() + ")");
        System.out.println("  accumulateAndGet(5, +): " + ai.accumulateAndGet(5, Integer::sum) + " (now: " + ai.get() + ")");
    }

    /**
     * CONCEPT: AtomicReference for lock-free immutable state updates.
     * Pattern: read current state, compute new state, CAS to update.
     * If CAS fails (another thread updated), retry.
     *
     * WHY: This pattern enables lock-free data structures like queues and stacks.
     * Java's ConcurrentLinkedQueue uses this internally.
     */
    private static void demonstrateAtomicReference() throws InterruptedException {
        System.out.println("\n--- AtomicReference (Immutable State Update) ---");

        // CONCEPT: Use records for immutable state - safe to share across threads
        record ServerConfig(String host, int port, int maxConnections) {}

        AtomicReference<ServerConfig> configRef = new AtomicReference<>(
                new ServerConfig("localhost", 8080, 100)
        );

        int numUpdaters = 5;
        CountDownLatch latch = new CountDownLatch(numUpdaters);

        // Multiple threads updating config concurrently (scale-out scenario)
        for (int t = 0; t < numUpdaters; t++) {
            final int thread = t;
            new Thread(() -> {
                ServerConfig current;
                ServerConfig updated;
                int retries = 0;
                do {
                    current = configRef.get();
                    // Compute new config based on current (e.g., increase connections by 10)
                    updated = new ServerConfig(
                            current.host(),
                            current.port() + thread,
                            current.maxConnections() + 10
                    );
                    retries++;
                    // CAS: only update if nobody else changed it since we read
                } while (!configRef.compareAndSet(current, updated));

                if (retries > 1) {
                    System.out.println("  Thread " + thread + " needed " + retries + " CAS retries (contention!)");
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        System.out.println("Final config: " + configRef.get());
        System.out.println("NOTE: Under high contention, many CAS retries = spinning (wasted CPU).");
        System.out.println("      If retries are frequent, reconsider using a lock.");
    }

    /**
     * CONCEPT: LongAdder vs AtomicLong for high-contention counters.
     *
     * AtomicLong: Single variable, all threads contend on the same cache line
     *             -> Cache line bouncing between CPU cores is expensive
     *
     * LongAdder: Striped counter (multiple cells, one per CPU core)
     *            Each thread updates its own cell -> minimal cache line contention
     *            sum() aggregates all cells -> slightly expensive, but reads are rare
     *
     * WHY: Use LongAdder for metrics/counters in high-throughput services.
     * Use AtomicLong when you need get() to be accurate and frequently checked.
     */
    private static void demonstrateLongAdderVsAtomicLong() throws InterruptedException {
        System.out.println("\n--- LongAdder vs AtomicLong (High Contention) ---");

        int numThreads = Runtime.getRuntime().availableProcessors() * 2;
        int incPerThread = 1_000_000;

        // Test AtomicLong
        AtomicLong atomicLong = new AtomicLong(0);
        long atomicTime = runContention(numThreads, () -> {
            for (int i = 0; i < incPerThread; i++) atomicLong.incrementAndGet();
        });

        // Test LongAdder
        LongAdder longAdder = new LongAdder();
        long adderTime = runContention(numThreads, () -> {
            for (int i = 0; i < incPerThread; i++) longAdder.increment();
        });

        System.out.printf("Threads: %d, Increments each: %,d%n", numThreads, incPerThread);
        System.out.printf("AtomicLong result: %,d  time: %,d ms%n", atomicLong.get(), atomicTime);
        System.out.printf("LongAdder  result: %,d  time: %,d ms%n", longAdder.sum(), adderTime);
        System.out.printf("LongAdder speedup: ~%.1fx%n",
                atomicTime > 0 ? (double) atomicTime / adderTime : 1.0);
        System.out.println("Use LongAdder for counters where increment >> read");
    }

    private static long runContention(int numThreads, Runnable task) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(numThreads);
        Thread[] threads = new Thread[numThreads];
        for (int t = 0; t < numThreads; t++) {
            threads[t] = new Thread(() -> {
                try {
                    start.await(); // All threads start simultaneously
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            threads[t].setDaemon(true);
            threads[t].start();
        }
        long startTime = System.currentTimeMillis();
        start.countDown(); // Release all threads
        done.await();
        return System.currentTimeMillis() - startTime;
    }

    /**
     * CONCEPT: Compare-And-Swap in detail - manual CAS loop pattern.
     * This is the foundation of all lock-free data structures.
     */
    private static void demonstrateCompareAndSwap() {
        System.out.println("\n--- Compare-And-Swap Mechanics ---");

        AtomicInteger state = new AtomicInteger(0);

        // CONCEPT: Manual CAS retry loop - the pattern inside AtomicInteger.incrementAndGet()
        // This is equivalent to: synchronized { state++; }
        // But without a lock!
        int oldValue, newValue;
        int attempts = 0;
        do {
            oldValue = state.get();            // Read current value
            newValue = oldValue + 1;           // Compute desired new value
            attempts++;
            // WHY: CAS succeeds only if state hasn't changed since we read it.
            // If another thread changed state, we retry with the fresh value.
        } while (!state.compareAndSet(oldValue, newValue));

        System.out.printf("CAS increment: %d -> %d (attempts: %d)%n", 0, state.get(), attempts);

        // State machine with CAS: safe state transitions without locks
        AtomicInteger stateMachine = new AtomicInteger(0); // 0=IDLE, 1=RUNNING, 2=STOPPED

        // Transition IDLE -> RUNNING (only succeeds if currently IDLE)
        boolean started = stateMachine.compareAndSet(0, 1);
        System.out.println("Transition IDLE->RUNNING: " + (started ? "succeeded" : "failed"));

        // Try to transition IDLE -> RUNNING again (should fail - already RUNNING)
        boolean startedAgain = stateMachine.compareAndSet(0, 1);
        System.out.println("Transition IDLE->RUNNING again: " + (startedAgain ? "succeeded" : "failed (already RUNNING)"));

        // Transition RUNNING -> STOPPED
        boolean stopped = stateMachine.compareAndSet(1, 2);
        System.out.println("Transition RUNNING->STOPPED: " + (stopped ? "succeeded" : "failed"));
    }

    /**
     * CONCEPT: The ABA Problem - a subtle bug in CAS-based algorithms.
     *
     * Thread 1 reads value A
     * Thread 2 changes A -> B -> A (value back to A!)
     * Thread 1's CAS(A, newValue) succeeds, even though the state changed!
     *
     * WHY: If "A" means "object not modified", the ABA problem causes Thread 1
     * to believe the object is unmodified when it was actually modified.
     *
     * Solution: AtomicStampedReference adds a stamp (version counter) to detect ABA.
     */
    private static void demonstrateAtomicABAIssue() {
        System.out.println("\n--- ABA Problem & AtomicStampedReference ---");

        // BAD: Plain AtomicReference susceptible to ABA
        AtomicReference<String> ref = new AtomicReference<>("A");
        String snapshot = ref.get();        // Thread 1 reads "A"
        ref.set("B");                        // Thread 2: A -> B
        ref.set("A");                        // Thread 2: B -> A (back to A!)
        boolean success = ref.compareAndSet(snapshot, "C"); // Thread 1: CAS succeeds!
        System.out.println("BAD: ABA CAS succeeded despite intermediate change: " + success);

        // GOOD: AtomicStampedReference detects ABA with version stamps
        AtomicStampedReference<String> stampedRef = new AtomicStampedReference<>("A", 0);
        int[] stampHolder = new int[1];
        String current = stampedRef.get(stampHolder);  // Thread 1 reads "A" with stamp 0
        int stamp = stampHolder[0];

        stampedRef.compareAndSet("A", "B", 0, 1); // Thread 2: A->B, stamp 0->1
        stampedRef.compareAndSet("B", "A", 1, 2); // Thread 2: B->A, stamp 1->2

        // Thread 1 tries CAS with OLD stamp (0) - fails because stamp is now 2
        boolean abaDetected = !stampedRef.compareAndSet(current, "C", stamp, stamp + 1);
        System.out.println("GOOD: ABA detected, CAS rejected: " + abaDetected);
        System.out.println("Current value: " + stampedRef.getReference() + ", stamp: " + stampedRef.getStamp());
    }

    /**
     * CONCEPT: Non-blocking (lock-free) stack using AtomicReference.
     * This is a simplified version of java.util.concurrent.ConcurrentLinkedDeque.
     *
     * WHY: Lock-free data structures scale better under high contention
     * because they never block (a thread can always make progress).
     * The trade-off is complexity and potential livelock under extreme contention.
     */
    static class NonBlockingStack<T> {
        private static class Node<T> {
            final T value;
            final Node<T> next;
            Node(T value, Node<T> next) {
                this.value = value;
                this.next = next;
            }
        }

        private final AtomicReference<Node<T>> head = new AtomicReference<>(null);

        public void push(T value) {
            Node<T> newHead;
            Node<T> oldHead;
            do {
                oldHead = head.get();
                newHead = new Node<>(value, oldHead);
                // CAS: only succeed if head hasn't changed since we read it
            } while (!head.compareAndSet(oldHead, newHead));
        }

        public T pop() {
            Node<T> oldHead;
            Node<T> newHead;
            do {
                oldHead = head.get();
                if (oldHead == null) return null; // Stack is empty
                newHead = oldHead.next;
            } while (!head.compareAndSet(oldHead, newHead));
            return oldHead.value;
        }

        public boolean isEmpty() { return head.get() == null; }
    }

    private static void demonstrateNonBlockingStack() throws InterruptedException {
        System.out.println("\n--- Non-Blocking Stack (Lock-Free) ---");

        NonBlockingStack<Integer> stack = new NonBlockingStack<>();
        int numThreads = 4;
        int opsPerThread = 1000;
        AtomicInteger pushCount = new AtomicInteger(0);
        AtomicInteger popCount = new AtomicInteger(0);

        CountDownLatch latch = new CountDownLatch(numThreads);
        for (int t = 0; t < numThreads; t++) {
            final int tId = t;
            new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    stack.push(tId * opsPerThread + i);
                    pushCount.incrementAndGet();
                }
                for (int i = 0; i < opsPerThread; i++) {
                    if (stack.pop() != null) popCount.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        System.out.printf("Pushed: %d, Popped: %d, Remaining: %s%n",
                pushCount.get(), popCount.get(), stack.isEmpty() ? "empty" : "some items");
        System.out.println("Lock-free stack: no locks, no blocking, fully concurrent.");
    }
}
