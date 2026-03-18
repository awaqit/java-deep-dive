package com.deepdive.month05.week20;

import java.util.concurrent.atomic.*;

/**
 * Week 20: LMAX Disruptor-Style Ring Buffer
 *
 * CONCEPT: The LMAX Disruptor is a high-performance inter-thread messaging library.
 * It was created by LMAX Exchange (financial trading) to achieve millions of
 * transactions per second with sub-microsecond latency.
 *
 * Why faster than BlockingQueue?
 * - BlockingQueue: Locks + condition variables (OS involvement) = microseconds
 * - Disruptor:     CAS + busy-wait + cache-friendly ring buffer = nanoseconds
 *
 * Key insights:
 * 1. Pre-allocate all objects in the ring buffer (no GC during operation)
 * 2. Power-of-2 ring size allows bit masking instead of modulo (faster)
 * 3. Separate "claims" (producers) from "commits" (available to consumers)
 * 4. Consumers read without locks (wait strategies: BusySpin, Yielding, Blocking)
 * 5. Ring buffer is an array = sequential memory = cache prefetch works!
 * 6. Each event slot pre-allocated - producer MUTATES in place (no allocation)
 *
 * Disruptor throughput: ~25 million events/second on commodity hardware
 * vs. ArrayBlockingQueue: ~1 million events/second
 *
 * Used by: LMAX Exchange, Apache Storm, Hazelcast, CoralMQ
 *
 * Official Disruptor: https://github.com/LMAX-Exchange/disruptor
 */
public class DisruptorPatternDemo {

    // ==================== RING BUFFER EVENT ====================

    /**
     * CONCEPT: Pre-allocated events. Producers don't create new objects,
     * they claim an existing slot and OVERWRITE it with new data.
     * This eliminates allocation entirely from the hot path!
     */
    static class OrderEvent {
        volatile long orderId;
        volatile double amount;
        volatile String type;
        volatile long timestamp;

        // CONCEPT: Object reuse - clear for next use
        void clear() {
            orderId = 0;
            amount = 0;
            type = null;
            timestamp = 0;
        }
    }

    // ==================== SIMPLIFIED RING BUFFER ====================

    /**
     * CONCEPT: Ring buffer with:
     * - Pre-allocated event objects (no GC)
     * - Power-of-2 capacity (bitwise masking for index)
     * - Sequence numbers for coordination (never wraps around - monotonic)
     * - Separation of claim (producer) and publish (visible to consumers)
     */
    static class SimpleRingBuffer {
        private final OrderEvent[] buffer;
        private final int mask; // capacity - 1, used for bitwise modulo
        private final int capacity;

        // Sequence numbers: monotonically increasing, never wrap
        private final AtomicLong producerSequence = new AtomicLong(-1);
        private final AtomicLong consumerSequence = new AtomicLong(-1);

        SimpleRingBuffer(int capacity) {
            // CONCEPT: Power-of-2 capacity for efficient modulo via bit masking
            if (Integer.bitCount(capacity) != 1) {
                throw new IllegalArgumentException("Capacity must be a power of 2");
            }
            this.capacity = capacity;
            this.mask = capacity - 1;
            this.buffer = new OrderEvent[capacity];

            // PRE-ALLOCATE all event objects!
            for (int i = 0; i < capacity; i++) {
                buffer[i] = new OrderEvent();
            }
        }

        /**
         * CONCEPT: Producer claims the next sequence number.
         * Returns the pre-allocated event slot for the producer to write into.
         * No new object created - just reuse the pre-allocated slot.
         */
        OrderEvent claimNext() {
            long next = producerSequence.incrementAndGet();
            // Wait if consumer hasn't processed the slot we're about to overwrite
            while (next - consumerSequence.get() > capacity) {
                Thread.onSpinWait(); // CONCEPT: Busy-wait (faster than blocking)
            }
            // Return the pre-allocated slot at this sequence
            return buffer[(int)(next & mask)]; // Bitwise AND = fast modulo
        }

        /**
         * CONCEPT: After writing to the claimed slot, the producer "publishes" it.
         * This makes it visible to consumers.
         * The sequence number IS the publication mechanism (no lock needed!).
         */
        // In full Disruptor: explicitly track "published" vs "claimed"
        // Here we simplify: data is visible when producerSequence advances

        /**
         * CONCEPT: Consumer reads the next available event.
         * Waits (busy-wait or yield) until producer has published.
         */
        OrderEvent consume() {
            long next = consumerSequence.incrementAndGet();
            // Wait until producer has published this sequence
            while (next > producerSequence.get()) {
                Thread.onSpinWait();
            }
            return buffer[(int)(next & mask)];
        }

        long getCapacity() { return capacity; }
        long getAvailableCount() {
            return producerSequence.get() - consumerSequence.get();
        }
    }

    // ==================== EVENT HANDLER ====================

    @FunctionalInterface
    interface EventHandler {
        void onEvent(OrderEvent event, long sequence, boolean endOfBatch);
    }

    // ==================== THROUGHPUT TEST ====================

    static void demonstrateThroughput() throws InterruptedException {
        System.out.println("\n--- Ring Buffer Throughput Test ---");

        int RING_SIZE = 1024; // Must be power of 2
        int EVENTS_TO_PROCESS = 1_000_000;

        SimpleRingBuffer ringBuffer = new SimpleRingBuffer(RING_SIZE);
        AtomicLong processedCount = new AtomicLong(0);
        AtomicLong totalAmount = new AtomicLong(0);

        // Consumer thread
        Thread consumer = new Thread(() -> {
            long lastSeq = -1;
            while (processedCount.get() < EVENTS_TO_PROCESS) {
                OrderEvent event = ringBuffer.consume();
                totalAmount.addAndGet((long) event.amount);
                processedCount.incrementAndGet();
                event.clear(); // Reset for reuse
            }
        }, "ring-consumer");

        consumer.setDaemon(true);
        consumer.start();

        // Measure throughput
        long start = System.currentTimeMillis();

        // Producer
        for (int i = 0; i < EVENTS_TO_PROCESS; i++) {
            OrderEvent event = ringBuffer.claimNext();
            // CONCEPT: Mutate in-place - no new object!
            event.orderId = i;
            event.amount = 10.0 + (i % 100);
            event.type = i % 2 == 0 ? "BUY" : "SELL";
            event.timestamp = System.nanoTime();
            // In full Disruptor: explicitly publish after writing
        }

        consumer.join(5000);
        long elapsed = System.currentTimeMillis() - start;
        long throughput = EVENTS_TO_PROCESS * 1000L / Math.max(elapsed, 1);

        System.out.printf("Events: %,d, Time: %,d ms%n", EVENTS_TO_PROCESS, elapsed);
        System.out.printf("Throughput: %,d events/second%n", throughput);
        System.out.printf("Processed: %,d, Total amount: %,d%n",
                processedCount.get(), totalAmount.get());
    }

    // ==================== MULTI-CONSUMER PATTERN ====================

    /**
     * CONCEPT: Disruptor supports multiple consumer patterns:
     * - Independent consumers: Each gets ALL events (different processing needs)
     * - Work pool: Events distributed across consumers (for parallelism)
     * - Dependency pipeline: Consumer B depends on Consumer A completing
     */
    static void demonstrateMultiConsumerPattern() {
        System.out.println("\n--- Multi-Consumer Patterns ---");
        System.out.println("Independent consumers (each gets all events):");
        System.out.println("  Events -> [Logger] (every event logged)");
        System.out.println("           [AuditWriter] (every event audited)");
        System.out.println("           [BusinessProcessor] (every event processed)");
        System.out.println();
        System.out.println("Work pool (events distributed):");
        System.out.println("  Events -> [Worker-1] (gets ~1/3 of events)");
        System.out.println("           [Worker-2] (gets ~1/3 of events)");
        System.out.println("           [Worker-3] (gets ~1/3 of events)");
        System.out.println();
        System.out.println("Pipeline (dependent consumers):");
        System.out.println("  Events -> [Validator] -> [Enricher] -> [Persister]");
        System.out.println("  Persister runs after Enricher has processed each event");
        System.out.println();
        System.out.println("LMAX Disruptor Java code:");
        System.out.println("  Disruptor<OrderEvent> disruptor = new Disruptor<>(");
        System.out.println("      OrderEvent::new, 1024, DaemonThreadFactory.INSTANCE);");
        System.out.println("  disruptor.handleEventsWith(logger, auditWriter)");
        System.out.println("           .then(businessProcessor);");
        System.out.println("  disruptor.start();");
        System.out.println("  RingBuffer<OrderEvent> ringBuffer = disruptor.getRingBuffer();");
    }

    /**
     * CONCEPT: Compare Disruptor with ArrayBlockingQueue
     */
    static void compareWithBlockingQueue() throws InterruptedException {
        System.out.println("\n--- Disruptor vs BlockingQueue Comparison ---");

        int ITERATIONS = 500_000;

        // ArrayBlockingQueue test
        java.util.concurrent.ArrayBlockingQueue<long[]> queue =
                new java.util.concurrent.ArrayBlockingQueue<>(1024);

        AtomicLong abqProcessed = new AtomicLong(0);
        Thread abqConsumer = new Thread(() -> {
            while (abqProcessed.get() < ITERATIONS) {
                try {
                    long[] item = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (item != null) abqProcessed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        abqConsumer.setDaemon(true);
        abqConsumer.start();

        long abqStart = System.currentTimeMillis();
        for (int i = 0; i < ITERATIONS; i++) {
            queue.put(new long[]{i, System.nanoTime()}); // Allocation!
        }
        abqConsumer.join(5000);
        long abqTime = System.currentTimeMillis() - abqStart;

        // Ring buffer test
        SimpleRingBuffer ringBuffer = new SimpleRingBuffer(1024);
        AtomicLong rbProcessed = new AtomicLong(0);
        Thread rbConsumer = new Thread(() -> {
            while (rbProcessed.get() < ITERATIONS) {
                OrderEvent event = ringBuffer.consume();
                rbProcessed.incrementAndGet();
                event.clear();
            }
        });
        rbConsumer.setDaemon(true);
        rbConsumer.start();

        long rbStart = System.currentTimeMillis();
        for (int i = 0; i < ITERATIONS; i++) {
            OrderEvent event = ringBuffer.claimNext();
            event.orderId = i;
            event.amount = 100.0;
        }
        rbConsumer.join(5000);
        long rbTime = System.currentTimeMillis() - rbStart;

        System.out.printf("ArrayBlockingQueue:  %,d ms (%,d events/sec) - allocates per message%n",
                abqTime, ITERATIONS * 1000L / Math.max(abqTime, 1));
        System.out.printf("Ring Buffer:         %,d ms (%,d events/sec) - zero allocation%n",
                rbTime, ITERATIONS * 1000L / Math.max(rbTime, 1));
        System.out.printf("Ring buffer speedup: ~%.1fx%n",
                abqTime > 0 ? (double) abqTime / rbTime : 1.0);
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== LMAX Disruptor-Style Ring Buffer Demo ===");

        demonstrateThroughput();
        demonstrateMultiConsumerPattern();
        compareWithBlockingQueue();

        System.out.println("\n--- When to Use the Disruptor ---");
        System.out.println("USE when:");
        System.out.println("  - Throughput > 1M events/second needed");
        System.out.println("  - Sub-microsecond latency required");
        System.out.println("  - GC pauses are unacceptable");
        System.out.println("  - Financial trading, game engines, high-speed networking");
        System.out.println();
        System.out.println("DON'T USE when:");
        System.out.println("  - Normal throughput requirements (<100K events/second)");
        System.out.println("  - I/O-bound workloads (virtual threads suffice)");
        System.out.println("  - Small team without low-latency expertise");
        System.out.println("  - Kafka/RabbitMQ handle your scale perfectly well");
    }
}
