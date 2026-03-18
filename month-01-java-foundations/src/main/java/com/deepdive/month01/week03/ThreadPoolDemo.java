package com.deepdive.month01.week03;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Week 3: Concurrency - Thread Pools
 *
 * CONCEPT: Thread creation is expensive (OS-level, ~1MB stack by default).
 * Thread pools amortize this cost by reusing threads across many tasks.
 *
 * ThreadPoolExecutor parameters:
 * - corePoolSize:    Threads kept alive even when idle
 * - maximumPoolSize: Maximum thread count under load
 * - keepAliveTime:   How long idle threads above core survive
 * - workQueue:       Queue for tasks when all core threads are busy
 * - threadFactory:   Customizes thread creation (name, daemon, priority)
 * - rejectedExecutionHandler: What happens when queue is full
 *
 * Queue strategies:
 * - LinkedBlockingQueue (unbounded): Can grow indefinitely, risks OOM
 * - ArrayBlockingQueue (bounded):    Backpressure - rejects when full
 * - SynchronousQueue:               No buffering - handoff only (used by CachedThreadPool)
 * - PriorityBlockingQueue:          Priority-based ordering
 *
 * Rejection policies:
 * - AbortPolicy (default):         Throw RejectedExecutionException
 * - CallerRunsPolicy:              Caller thread executes the task (natural backpressure)
 * - DiscardPolicy:                 Silently drop newest task
 * - DiscardOldestPolicy:           Drop oldest queued task, retry newest
 *
 * Java 21: Virtual Threads (Project Loom)
 * - Executors.newVirtualThreadPerTaskExecutor()
 * - Ideal for I/O-bound tasks (each task gets its own virtual thread cheaply)
 */
public class ThreadPoolDemo {

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        System.out.println("=== Thread Pool Deep Dive ===");

        demonstrateFixedThreadPool();
        demonstrateCachedThreadPool();
        demonstrateCustomThreadPoolExecutor();
        demonstrateCustomThreadFactory();
        demonstrateVirtualThreads();
        demonstrateRejectionPolicies();
    }

    /**
     * CONCEPT: Fixed thread pool - bounded parallelism.
     * Best for CPU-bound work where parallelism = number of CPU cores.
     * Rule of thumb: corePoolSize = Runtime.getRuntime().availableProcessors()
     */
    private static void demonstrateFixedThreadPool() throws InterruptedException, ExecutionException {
        System.out.println("\n--- Fixed Thread Pool ---");
        int cpuCores = Runtime.getRuntime().availableProcessors();
        System.out.println("Available CPU cores: " + cpuCores);

        ExecutorService pool = Executors.newFixedThreadPool(cpuCores);
        AtomicInteger completedTasks = new AtomicInteger(0);

        // Submit CPU-bound tasks
        Future<Long>[] futures = new Future[cpuCores * 2];
        for (int i = 0; i < futures.length; i++) {
            final int taskId = i;
            futures[i] = pool.submit(() -> {
                // Simulate CPU-bound computation
                long sum = 0;
                for (int j = 0; j < 1_000_000; j++) sum += j;
                completedTasks.incrementAndGet();
                return sum;
            });
        }

        long totalSum = 0;
        for (Future<Long> f : futures) totalSum += f.get();

        System.out.printf("Fixed pool (%d threads): completed %d tasks, totalSum=%d%n",
                cpuCores, completedTasks.get(), totalSum);

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    /**
     * CONCEPT: Cached thread pool - unbounded thread count, unlimited queue.
     * Best for I/O-bound tasks with short bursts of work.
     * WARNING: Can create thousands of threads under load -> OutOfMemoryError
     * In production, prefer virtual threads for I/O-bound work (Java 21+)
     */
    private static void demonstrateCachedThreadPool() throws InterruptedException {
        System.out.println("\n--- Cached Thread Pool (use with caution) ---");

        ExecutorService pool = Executors.newCachedThreadPool();
        CountDownLatch latch = new CountDownLatch(10);

        long start = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            final int taskId = i;
            pool.execute(() -> {
                try {
                    // Simulate I/O wait
                    Thread.sleep(50);
                    System.out.println("  Task " + taskId + " on " + Thread.currentThread().getName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        System.out.printf("All 10 I/O tasks completed in %d ms (all ran in parallel)%n",
                System.currentTimeMillis() - start);

        pool.shutdown();
        System.out.println("WARNING: newCachedThreadPool can create unlimited threads!");
        System.out.println("         Prefer virtual threads for I/O-bound work.");
    }

    /**
     * CONCEPT: Custom ThreadPoolExecutor gives full control over all parameters.
     * This is what you should use in production - explicit configuration is better than implicit.
     *
     * WHY: Understanding the ThreadPoolExecutor queue + thread interaction is essential:
     * 1. If running threads < corePoolSize: create new thread
     * 2. If corePoolSize reached: add to queue
     * 3. If queue full AND running < maxPoolSize: create new thread
     * 4. If queue full AND maxPoolSize reached: reject
     */
    private static void demonstrateCustomThreadPoolExecutor() throws InterruptedException {
        System.out.println("\n--- Custom ThreadPoolExecutor ---");

        int coreSize = 2;
        int maxSize = 4;
        int queueCapacity = 10;

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreSize,                              // Always keep 2 threads alive
                maxSize,                               // Burst up to 4 threads
                60L, TimeUnit.SECONDS,                 // Extra threads die after 60s idle
                new ArrayBlockingQueue<>(queueCapacity), // Bounded queue - backpressure
                new CountingThreadFactory("worker"),   // Named threads
                new ThreadPoolExecutor.CallerRunsPolicy() // Backpressure: caller executes task
        );

        // Enable monitoring
        executor.prestartAllCoreThreads(); // Start core threads immediately

        System.out.println("Pool stats (initial):");
        printPoolStats(executor);

        CountDownLatch latch = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            final int taskId = i;
            executor.execute(() -> {
                try {
                    Thread.sleep(100); // simulate work
                    System.out.printf("  Task %2d completed by %s%n",
                            taskId, Thread.currentThread().getName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        System.out.println("Pool stats (while running):");
        printPoolStats(executor);

        latch.await();
        System.out.println("Pool stats (after completion):");
        printPoolStats(executor);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    private static void printPoolStats(ThreadPoolExecutor executor) {
        System.out.printf("  Active: %d, Pool size: %d, Queue: %d, Completed: %d%n",
                executor.getActiveCount(),
                executor.getPoolSize(),
                executor.getQueue().size(),
                executor.getCompletedTaskCount());
    }

    /**
     * CONCEPT: Custom ThreadFactory - crucial for production observability.
     * Named threads show up in thread dumps, stack traces, and monitoring tools.
     *
     * WHY: Without named threads, you get "pool-1-thread-1" which is meaningless
     * during incident investigation. Always name your thread pools!
     */
    static class CountingThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger threadCount = new AtomicInteger(1);
        private final ThreadGroup group;

        CountingThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
            this.group = Thread.currentThread().getThreadGroup();
        }

        @Override
        public Thread newThread(Runnable r) {
            String threadName = namePrefix + "-thread-" + threadCount.getAndIncrement();
            Thread t = new Thread(group, r, threadName, 0);
            t.setDaemon(false); // WHY: Non-daemon threads keep JVM alive
            t.setPriority(Thread.NORM_PRIORITY);
            System.out.println("  Created thread: " + threadName);
            return t;
        }
    }

    private static void demonstrateCustomThreadFactory() {
        System.out.println("\n--- Custom ThreadFactory ---");
        // Demonstrate via demonstrateCustomThreadPoolExecutor above
        System.out.println("(Custom ThreadFactory demonstrated in CustomThreadPoolExecutor section above)");
        System.out.println("Named threads appear in thread dumps: jstack <pid> | grep 'worker-thread'");
        System.out.println("Also visible in: jcmd <pid> Thread.print");
    }

    /**
     * CONCEPT: Virtual Threads (Java 21 - Project Loom)
     * Virtual threads are lightweight threads managed by the JVM, not the OS.
     * They are ideal for I/O-bound workloads (HTTP calls, DB queries, etc.)
     *
     * - Platform thread: OS thread, ~1MB stack, OS scheduler
     * - Virtual thread: JVM-managed, few KB heap, JVM scheduler
     *   Can have millions of virtual threads with the same hardware
     *
     * WHY: Traditional thread pools for I/O-bound work waste OS threads sitting idle
     * waiting for I/O. Virtual threads block the virtual thread (not OS thread),
     * while the carrier (OS) thread serves other virtual threads.
     *
     * When to use virtual threads:
     * - YES: HTTP request handlers, DB calls, file I/O, RPC calls
     * - NO: CPU-bound loops (no benefit, same as platform threads for computation)
     */
    private static void demonstrateVirtualThreads() throws InterruptedException {
        System.out.println("\n--- Virtual Threads (Java 21) ---");

        int taskCount = 10_000;
        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicInteger completed = new AtomicInteger(0);

        // Virtual thread per task executor
        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.currentTimeMillis();

            for (int i = 0; i < taskCount; i++) {
                virtualExecutor.submit(() -> {
                    try {
                        // Simulates I/O wait - virtual thread parks, OS thread serves others
                        Thread.sleep(10);
                        completed.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            System.out.printf("Virtual threads: %,d tasks with 10ms I/O each%n", taskCount);
            System.out.printf("Total time: %d ms (would be ~%,d ms with %d platform threads)%n",
                    elapsed,
                    taskCount * 10 / Runtime.getRuntime().availableProcessors(),
                    Runtime.getRuntime().availableProcessors());
            System.out.printf("Completed: %d tasks%n", completed.get());
        }

        // NOTE: Virtual threads are always daemon threads
        // NOTE: Don't pool virtual threads - create one per task
        // NOTE: Avoid pinning: synchronized blocks can pin virtual thread to carrier
        //       Use ReentrantLock instead of synchronized in virtual thread code
        System.out.println("NOTE: Use ReentrantLock instead of synchronized with virtual threads");
        System.out.println("      synchronized can 'pin' virtual thread to OS carrier thread");
    }

    /**
     * CONCEPT: Rejection policies handle task overflow gracefully.
     * In production, CallerRunsPolicy provides natural backpressure,
     * preventing unbounded queue growth and OOM errors.
     */
    private static void demonstrateRejectionPolicies() throws InterruptedException {
        System.out.println("\n--- Rejection Policies ---");

        // Small pool + small queue to demonstrate rejection
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2),
                Executors.defaultThreadFactory(),
                (r, e) -> System.out.println("  REJECTED: task (queue full, pool full)")
        );

        // Submit more tasks than pool + queue can hold
        for (int i = 0; i < 5; i++) {
            final int id = i;
            try {
                executor.execute(() -> {
                    try {
                        Thread.sleep(100);
                        System.out.println("  Completed task " + id);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
                System.out.println("  Submitted task " + id);
            } catch (RejectedExecutionException e) {
                System.out.println("  Exception rejected task " + id);
            }
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("Rejection policy comparison:");
        System.out.println("  AbortPolicy:        Throw exception (caller must handle)");
        System.out.println("  CallerRunsPolicy:   Caller executes task (slows submitter = backpressure)");
        System.out.println("  DiscardPolicy:      Silent drop (data loss risk)");
        System.out.println("  DiscardOldestPolicy: Drop oldest from queue (fresh > stale)");
    }
}
