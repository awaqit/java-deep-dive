package com.deepdive.month01.week03;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Week 3: Producer-Consumer Pattern
 *
 * CONCEPT: Producer-Consumer decouples the rate of work production from consumption.
 * Producers generate work items; consumers process them asynchronously.
 * A shared buffer (queue) absorbs the difference in rates.
 *
 * This is foundational to message queues (Kafka, RabbitMQ), async processing pipelines,
 * thread pools, and event-driven architectures.
 *
 * Key components:
 * - BlockingQueue: Thread-safe queue with blocking put/take operations
 *   - ArrayBlockingQueue:   Bounded, array-backed, fair ordering option
 *   - LinkedBlockingQueue:  Optionally bounded, linked-list backed
 *   - PriorityBlockingQueue: Unbounded, priority ordering, no blocking on put
 *   - LinkedTransferQueue:  Optimized for handoff, lower latency
 *   - SynchronousQueue:     Zero capacity, each put waits for take (rendezvous)
 *
 * Java 21 Structured Concurrency (JEP 453 preview):
 *   StructuredTaskScope enables cleaner producer-consumer lifecycle management.
 */
public class ProducerConsumerDemo {

    // CONCEPT: Poison pill pattern - a sentinel value to signal "no more work"
    private static final WorkItem POISON_PILL = new WorkItem(-1, "STOP");

    // Java 21 record: immutable data carrier, auto-generates equals/hashCode/toString
    record WorkItem(int id, String payload) {}

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Producer-Consumer Pattern ===");

        demonstrateBasicProducerConsumer();
        demonstrateMultipleProducersConsumers();
        demonstrateBackpressure();
    }

    /**
     * CONCEPT: Basic Producer-Consumer with a bounded BlockingQueue.
     *
     * BlockingQueue.put():  Blocks if queue is full (backpressure to producer)
     * BlockingQueue.take(): Blocks if queue is empty (consumer waits for work)
     *
     * WHY: The bounded queue is crucial. An unbounded queue means the producer
     * never slows down, potentially consuming all heap memory.
     */
    private static void demonstrateBasicProducerConsumer() throws InterruptedException {
        System.out.println("\n--- Basic Producer-Consumer ---");

        BlockingQueue<WorkItem> queue = new ArrayBlockingQueue<>(10); // Bounded: max 10 items
        AtomicInteger processedCount = new AtomicInteger(0);

        // Producer thread
        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < 20; i++) {
                    WorkItem item = new WorkItem(i, "payload-" + i);
                    queue.put(item); // Blocks if queue is full - natural backpressure!
                    System.out.println("  Produced: " + item.id() + " (queue size: " + queue.size() + ")");
                    if (i % 5 == 0) Thread.sleep(10); // Simulate variable production rate
                }
                queue.put(POISON_PILL); // Signal consumer to stop
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "producer");

        // Consumer thread
        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    WorkItem item = queue.take(); // Blocks if queue is empty
                    if (item == POISON_PILL) {
                        System.out.println("  Consumer received POISON PILL - stopping");
                        break;
                    }
                    // Simulate processing time
                    Thread.sleep(15);
                    processedCount.incrementAndGet();
                    System.out.println("  Consumed: " + item.id() + " (queue size: " + queue.size() + ")");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "consumer");

        long start = System.currentTimeMillis();
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        System.out.printf("Processed %d items in %d ms%n",
                processedCount.get(), System.currentTimeMillis() - start);
    }

    /**
     * CONCEPT: Multiple producers and consumers - the general pattern used in
     * thread pools, Kafka consumer groups, and batch processing systems.
     *
     * WHY: Multiple consumers scale throughput. Multiple producers allow
     * different parts of the system to feed the same pipeline.
     *
     * NOTE: The poison pill pattern becomes tricky with multiple consumers -
     * you need one poison pill per consumer, or use a separate shutdown signal.
     */
    private static void demonstrateMultipleProducersConsumers() throws InterruptedException {
        System.out.println("\n--- Multiple Producers & Consumers ---");

        int numProducers = 3;
        int numConsumers = 2;
        int itemsPerProducer = 10;

        BlockingQueue<WorkItem> queue = new LinkedBlockingQueue<>(50);
        AtomicInteger itemIdCounter = new AtomicInteger(0);
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicLong totalProcessingTime = new AtomicLong(0);
        CountDownLatch producersDone = new CountDownLatch(numProducers);
        CountDownLatch consumersDone = new CountDownLatch(numConsumers);

        // Multiple producers
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            Thread producer = new Thread(() -> {
                try {
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int id = itemIdCounter.getAndIncrement();
                        queue.put(new WorkItem(id, "from-producer-" + producerId));
                        Thread.sleep(5); // Simulate production time
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                }
            }, "producer-" + p);
            producer.setDaemon(true);
            producer.start();
        }

        // Multiple consumers - stop when producers are done AND queue is empty
        for (int c = 0; c < numConsumers; c++) {
            final int consumerId = c;
            Thread consumer = new Thread(() -> {
                try {
                    while (true) {
                        // Poll with timeout - allows checking if all producers are done
                        WorkItem item = queue.poll(200, TimeUnit.MILLISECONDS);
                        if (item == null) {
                            // Check if all producers finished and queue is empty
                            if (producersDone.getCount() == 0 && queue.isEmpty()) {
                                break;
                            }
                            continue;
                        }
                        long procStart = System.nanoTime();
                        Thread.sleep(10); // Simulate processing
                        totalProcessingTime.addAndGet(System.nanoTime() - procStart);
                        processedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumersDone.countDown();
                }
            }, "consumer-" + c);
            consumer.setDaemon(true);
            consumer.start();
        }

        producersDone.await();
        consumersDone.await(10, TimeUnit.SECONDS);

        int expected = numProducers * itemsPerProducer;
        System.out.printf("Producers: %d, Consumers: %d%n", numProducers, numConsumers);
        System.out.printf("Expected: %d, Processed: %d%n", expected, processedCount.get());
        System.out.printf("Avg processing time per item: %,d µs%n",
                processedCount.get() > 0 ? totalProcessingTime.get() / processedCount.get() / 1000 : 0);
    }

    /**
     * CONCEPT: Backpressure in producer-consumer systems.
     * When consumers are slower than producers, the system must handle the imbalance.
     *
     * Strategies:
     * 1. Block producer (put() blocks) - simplest, avoids OOM, may cascade
     * 2. Drop items (offer() returns false) - data loss, fast producers
     * 3. Add more consumers - auto-scaling
     * 4. Apply rate limiting to producers
     * 5. Use Reactive Streams (Project Reactor) which has first-class backpressure
     */
    private static void demonstrateBackpressure() throws InterruptedException {
        System.out.println("\n--- Backpressure Demonstration ---");

        int queueCapacity = 5;
        BlockingQueue<WorkItem> queue = new ArrayBlockingQueue<>(queueCapacity);
        AtomicInteger dropped = new AtomicInteger(0);
        AtomicInteger accepted = new AtomicInteger(0);

        // Fast producer
        Thread producer = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                WorkItem item = new WorkItem(i, "item-" + i);
                // offer(): non-blocking put - returns false if queue is full
                boolean enqueued = queue.offer(item);
                if (enqueued) {
                    accepted.incrementAndGet();
                } else {
                    dropped.incrementAndGet(); // Backpressure response: drop
                }
            }
        }, "fast-producer");

        // Slow consumer
        Thread consumer = new Thread(() -> {
            int consumed = 0;
            try {
                while (consumed < 50 || !queue.isEmpty()) {
                    WorkItem item = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (item == null) break;
                    Thread.sleep(20); // Slow processing
                    consumed++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "slow-consumer");

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        System.out.printf("Queue capacity: %d, Producer sent: 50 items%n", queueCapacity);
        System.out.printf("Accepted: %d, Dropped (backpressure): %d%n", accepted.get(), dropped.get());
        System.out.println("Backpressure strategy: OFFER (drop) - suitable for best-effort systems");
        System.out.println("Alternatives:");
        System.out.println("  PUT (block): guarantees delivery, may cause upstream backpressure");
        System.out.println("  Reactive Streams: first-class backpressure with demand signaling");
    }
}
