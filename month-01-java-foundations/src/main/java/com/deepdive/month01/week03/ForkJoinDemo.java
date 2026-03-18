package com.deepdive.month01.week03;

import java.util.Arrays;
import java.util.concurrent.*;

/**
 * Week 3: ForkJoin Framework
 *
 * CONCEPT: ForkJoinPool implements work-stealing parallelism.
 * It's optimized for divide-and-conquer algorithms where tasks recursively
 * split themselves into smaller subtasks (fork), then combine results (join).
 *
 * Work-stealing algorithm:
 * - Each worker thread has its own deque (double-ended queue)
 * - Worker pushes tasks to the head of its deque
 * - When idle, worker steals tasks from the TAIL of other workers' deques
 * - This reduces contention: owner takes from head, thief takes from tail
 *
 * ForkJoinPool vs ThreadPoolExecutor:
 * - ForkJoinPool: Divide-and-conquer, work-stealing, RecursiveTask/Action
 * - ThreadPoolExecutor: Independent tasks, blocking I/O, uniform task size
 *
 * Java's parallel streams use the common ForkJoinPool:
 *   ForkJoinPool.commonPool()  // shared, parallelism = CPU cores - 1
 *
 * Key classes:
 * - RecursiveTask<V>:   Returns a result (like Callable in regular pools)
 * - RecursiveAction:    No return value (like Runnable)
 * - ForkJoinTask<V>:    Base class, supports fork()/join()/invoke()
 *
 * Java 21: Virtual Threads in ForkJoinPool carrier threads
 */
public class ForkJoinDemo {

    // CONCEPT: Threshold below which we switch to sequential (avoid over-splitting)
    private static final int SEQUENTIAL_THRESHOLD = 1000;

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        System.out.println("=== ForkJoin Framework Demo ===");

        int[] data = generateRandomArray(100_000);

        demonstrateMergeSort(data.clone());
        demonstrateParallelSum(data.clone());
        demonstrateWorkStealing();
        demonstrateCommonPool();
    }

    /**
     * CONCEPT: Merge sort using ForkJoinPool - classic divide-and-conquer.
     *
     * Algorithm:
     * 1. If array is small enough (< threshold), sort sequentially
     * 2. Otherwise, fork two tasks to sort left/right halves in parallel
     * 3. Join (wait for) both tasks
     * 4. Merge the sorted halves
     *
     * WHY: Merge sort is O(n log n) and highly parallelizable because
     * the left and right halves are completely independent.
     */
    static class MergeSortTask extends RecursiveAction {
        private final int[] array;
        private final int start;
        private final int end;

        MergeSortTask(int[] array, int start, int end) {
            this.array = array;
            this.start = start;
            this.end = end;
        }

        @Override
        protected void compute() {
            int length = end - start;

            if (length <= SEQUENTIAL_THRESHOLD) {
                // CONCEPT: Base case - sequential sort for small arrays
                Arrays.sort(array, start, end);
                return;
            }

            int mid = start + length / 2;

            // CONCEPT: fork() - submit left half as a new task to the pool
            // The current thread will work on the right half while left half runs in parallel
            MergeSortTask leftTask = new MergeSortTask(array, start, mid);
            MergeSortTask rightTask = new MergeSortTask(array, mid, end);

            // WHY: invokeAll is more efficient than fork+join - it uses the current
            // thread for one task and forks the other, reducing thread switching
            invokeAll(leftTask, rightTask);

            // Merge the sorted halves
            merge(array, start, mid, end);
        }

        private void merge(int[] arr, int start, int mid, int end) {
            int[] temp = Arrays.copyOfRange(arr, start, end);
            int left = 0, right = mid - start, k = start;
            int leftEnd = mid - start;

            while (left < leftEnd && right < (end - start)) {
                if (temp[left] <= temp[right]) {
                    arr[k++] = temp[left++];
                } else {
                    arr[k++] = temp[right++];
                }
            }
            while (left < leftEnd) arr[k++] = temp[left++];
            while (right < (end - start)) arr[k++] = temp[right++];
        }
    }

    private static void demonstrateMergeSort(int[] data) {
        System.out.println("\n--- Parallel Merge Sort ---");
        System.out.println("Array size: " + data.length);

        // Sequential sort for comparison
        int[] sequential = data.clone();
        long seqStart = System.currentTimeMillis();
        Arrays.sort(sequential);
        long seqTime = System.currentTimeMillis() - seqStart;

        // Parallel ForkJoin sort
        int[] parallel = data.clone();
        ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
        long parStart = System.currentTimeMillis();
        pool.invoke(new MergeSortTask(parallel, 0, parallel.length));
        long parTime = System.currentTimeMillis() - parStart;
        pool.shutdown();

        System.out.printf("Sequential Arrays.sort: %d ms%n", seqTime);
        System.out.printf("Parallel ForkJoin sort: %d ms%n", parTime);
        System.out.println("Sorted correctly: " + Arrays.equals(sequential, parallel));
        System.out.println("NOTE: Parallel overhead can exceed benefit for small arrays.");
        System.out.println("      Tune SEQUENTIAL_THRESHOLD based on array size and CPU cores.");
    }

    /**
     * CONCEPT: RecursiveTask<V> - returns a value, like a parallel reduce.
     * This is the pattern used by parallel streams' reduce/collect operations.
     */
    static class ParallelSumTask extends RecursiveTask<Long> {
        private final int[] array;
        private final int start;
        private final int end;

        ParallelSumTask(int[] array, int start, int end) {
            this.array = array;
            this.start = start;
            this.end = end;
        }

        @Override
        protected Long compute() {
            int length = end - start;

            if (length <= SEQUENTIAL_THRESHOLD) {
                // Base case: compute sum sequentially
                long sum = 0;
                for (int i = start; i < end; i++) sum += array[i];
                return sum;
            }

            int mid = start + length / 2;
            ParallelSumTask left = new ParallelSumTask(array, start, mid);
            ParallelSumTask right = new ParallelSumTask(array, mid, end);

            // WHY: fork() the left task first (it runs in a different thread),
            // then compute right in the current thread, then join left.
            // This is the standard ForkJoin idiom.
            left.fork();
            long rightResult = right.compute(); // Current thread computes right
            long leftResult = left.join();      // Wait for forked left task

            return leftResult + rightResult;
        }
    }

    private static void demonstrateParallelSum(int[] data) throws ExecutionException, InterruptedException {
        System.out.println("\n--- Parallel Sum with RecursiveTask ---");

        // Sequential sum
        long seqStart = System.nanoTime();
        long seqSum = 0;
        for (int val : data) seqSum += val;
        long seqTime = System.nanoTime() - seqStart;

        // Parallel ForkJoin sum
        ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
        long parStart = System.nanoTime();
        long parSum = pool.submit(new ParallelSumTask(data, 0, data.length)).get();
        long parTime = System.nanoTime() - parStart;
        pool.shutdown();

        // Parallel stream sum (uses common ForkJoinPool under the hood)
        long streamStart = System.nanoTime();
        long streamSum = Arrays.stream(data).asLongStream().parallel().sum();
        long streamTime = System.nanoTime() - streamStart;

        System.out.printf("Sequential sum:      %,d  (%,d µs)%n", seqSum, seqTime / 1000);
        System.out.printf("ForkJoin sum:        %,d  (%,d µs)%n", parSum, parTime / 1000);
        System.out.printf("Parallel stream sum: %,d  (%,d µs)%n", streamSum, streamTime / 1000);
        System.out.println("Sums match: " + (seqSum == parSum && parSum == streamSum));
    }

    /**
     * CONCEPT: Work-stealing in action - uneven task sizes benefit most.
     * When one thread finishes its tasks, it steals from busy threads.
     */
    private static void demonstrateWorkStealing() throws InterruptedException {
        System.out.println("\n--- Work-Stealing Demo ---");
        System.out.println("Work-stealing: idle workers steal from busy workers' queues.");
        System.out.println("This provides automatic load balancing for uneven task sizes.");

        ForkJoinPool pool = new ForkJoinPool(4);

        // Create uneven tasks: some take much longer than others
        ForkJoinTask<?>[] tasks = new ForkJoinTask[20];
        long[] taskTimes = new long[20];

        for (int i = 0; i < tasks.length; i++) {
            final int idx = i;
            final long sleepMs = (i % 4 == 0) ? 50 : 5; // 1 in 4 tasks is "heavy"
            tasks[i] = pool.submit(() -> {
                long start = System.currentTimeMillis();
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                taskTimes[idx] = System.currentTimeMillis() - start;
                return null;
            });
        }

        for (ForkJoinTask<?> task : tasks) task.quietlyJoin();
        pool.shutdown();

        long heavy = Arrays.stream(taskTimes).filter(t -> t >= 40).count();
        long light = Arrays.stream(taskTimes).filter(t -> t < 40).count();
        System.out.printf("Heavy tasks (50ms): %d, Light tasks (5ms): %d%n", heavy, light);
        System.out.println("Work-stealing ensures no thread is idle while others are overloaded.");
    }

    /**
     * CONCEPT: commonPool() is shared across all parallel stream operations
     * and ForkJoinTask.invoke() calls that don't specify a pool.
     *
     * WHY: Be careful with blocking operations in commonPool!
     * Blocking tasks consume carrier threads, reducing parallelism for streams.
     * For blocking ForkJoin tasks, use a custom ForkJoinPool with managedBlocking.
     */
    private static void demonstrateCommonPool() {
        System.out.println("\n--- Common ForkJoinPool ---");
        ForkJoinPool common = ForkJoinPool.commonPool();
        System.out.println("Common pool parallelism: " + common.getParallelism());
        System.out.println("(= available processors - 1, or set via -Djava.util.concurrent.ForkJoinPool.common.parallelism)");

        // Parallel stream uses commonPool automatically
        long sum = java.util.stream.LongStream.rangeClosed(1, 1_000_000)
                .parallel()
                .sum();
        System.out.println("Parallel stream sum 1..1M: " + sum);

        System.out.println("\nWARNING: Don't run blocking operations in commonPool tasks!");
        System.out.println("         Use a custom ForkJoinPool or virtual threads instead.");
        System.out.println("         Blocking in commonPool starves parallel streams application-wide.");
    }

    private static int[] generateRandomArray(int size) {
        java.util.Random rng = new java.util.Random(42);
        int[] arr = new int[size];
        for (int i = 0; i < size; i++) arr[i] = rng.nextInt(1_000_000);
        return arr;
    }
}
