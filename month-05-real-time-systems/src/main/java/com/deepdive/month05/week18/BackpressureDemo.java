package com.deepdive.month05.week18;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Week 18: Backpressure in Reactive Systems
 *
 * CONCEPT: Backpressure is the ability of a Subscriber to signal to a Publisher
 * how many items it can handle. This prevents fast producers from overwhelming slow consumers.
 *
 * Without backpressure:
 * Producer: [item1][item2][item3]...[item10000] -> buffer overflows -> OOM or data loss
 *
 * With backpressure:
 * Subscriber says "request(10)" -> Publisher sends 10 -> Subscriber processes -> "request(10)"
 *
 * Backpressure strategies (when subscriber can't keep up):
 * - DROP:          Discard items when buffer is full (fire-and-forget events)
 * - BUFFER:        Queue up to N items, then drop or block
 * - LATEST:        Keep only the most recent item (sensor data, live feeds)
 * - ERROR:         Signal error when overwhelmed (strict, no data loss)
 * - BLOCK:         Block the producer (like BlockingQueue.put())
 *
 * In Project Reactor:
 * - Flux.onBackpressureDrop(Consumer<T> onDrop)
 * - Flux.onBackpressureBuffer(maxSize, BufferOverflowStrategy.DROP_OLDEST)
 * - Flux.onBackpressureLatest()
 * - Flux.onBackpressureError()
 *
 * In Reactive Streams spec:
 * - Subscription.request(n): Subscriber asks for n items
 * - Subscription.cancel(): Subscriber stops receiving items
 *
 * Real-world: Kafka provides natural backpressure.
 * Consumer explicitly polls: it processes at its own pace.
 * No polling = no consumption = Kafka buffers on disk (within retention).
 */
public class BackpressureDemo {

    // Simulate the Reactive Streams subscription protocol
    interface ReactiveSubscriber<T> {
        void onSubscribe(ReactiveSubscription subscription);
        void onNext(T item);
        void onError(Throwable t);
        void onComplete();
    }

    interface ReactiveSubscription {
        void request(long n);
        void cancel();
    }

    // ==================== BACKPRESSURE STRATEGIES ====================

    static class BackpressureExperiment {
        private final AtomicLong produced = new AtomicLong(0);
        private final AtomicLong consumed = new AtomicLong(0);
        private final AtomicLong dropped = new AtomicLong(0);

        /**
         * CONCEPT: DROP strategy - discard items when consumer is busy.
         * Good for: sensor readings, live metrics where freshness > completeness.
         * Bad for: financial transactions, audit logs.
         */
        void demonstrateDrop() throws InterruptedException {
            System.out.println("\n[DROP Strategy] Fast producer, slow consumer");
            produced.set(0); consumed.set(0); dropped.set(0);

            BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(5); // Small buffer

            // Fast producer
            Thread producer = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    boolean added = queue.offer(i); // Non-blocking, returns false if full
                    if (added) produced.incrementAndGet();
                    else dropped.incrementAndGet(); // DROP: item discarded
                }
            }, "producer");

            // Slow consumer
            Thread consumer = new Thread(() -> {
                try {
                    while (consumed.get() < 20) {
                        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item != null) {
                            Thread.sleep(20); // Slow processing
                            consumed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "consumer");

            producer.start();
            consumer.start();
            producer.join();
            consumer.join(3000);

            System.out.printf("  Produced: %d, Consumed: %d, Dropped: %d (%.0f%% loss)%n",
                    produced.get() + dropped.get(), consumed.get(), dropped.get(),
                    dropped.get() > 0 ? (double) dropped.get() / (produced.get() + dropped.get()) * 100 : 0);
            System.out.println("  Trade-off: Fast producer, some data loss accepted");
        }

        /**
         * CONCEPT: BUFFER strategy - queue up to N items.
         * Consumer processes at its own pace; producer blocks when buffer full.
         * This is the classic producer-consumer pattern (see month 1).
         */
        void demonstrateBuffer() throws InterruptedException {
            System.out.println("\n[BUFFER Strategy] Bounded queue provides backpressure");
            produced.set(0); consumed.set(0);

            // Bounded buffer = backpressure to producer
            BlockingQueue<Integer> queue = new LinkedBlockingQueue<>(10);

            Thread producer = new Thread(() -> {
                try {
                    for (int i = 0; i < 30; i++) {
                        queue.put(i); // BLOCKS when full - backpressure!
                        produced.incrementAndGet();
                    }
                    queue.put(-1); // Sentinel
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "producer");

            Thread consumer = new Thread(() -> {
                try {
                    while (true) {
                        Integer item = queue.take();
                        if (item == -1) break;
                        Thread.sleep(30); // Slow consumer
                        consumed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "consumer");

            long start = System.currentTimeMillis();
            producer.start();
            consumer.start();
            producer.join();
            consumer.join(5000);
            long elapsed = System.currentTimeMillis() - start;

            System.out.printf("  Produced: %d, Consumed: %d, Time: %dms%n",
                    produced.get(), consumed.get(), elapsed);
            System.out.println("  Trade-off: No data loss, producer slows to consumer pace");
        }

        /**
         * CONCEPT: LATEST strategy - keep only most recent item.
         * Good for: UI updates, sensor readings where stale data is useless.
         * Example: Stock price ticker - show latest price, don't queue old ones.
         */
        void demonstrateLatest() throws InterruptedException {
            System.out.println("\n[LATEST Strategy] Only keep most recent value");
            produced.set(0); consumed.set(0); dropped.set(0);

            AtomicReference<Integer> latestItem = new AtomicReference<>(null);
            AtomicBoolean done = new AtomicBoolean(false);

            Thread producer = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    Integer old = latestItem.getAndSet(i); // Always replace with latest
                    if (old != null) dropped.incrementAndGet(); // Old value was overwritten
                    produced.incrementAndGet();
                    // No sleep - produce as fast as possible
                }
                done.set(true);
            }, "producer");

            Thread consumer = new Thread(() -> {
                try {
                    while (!done.get() || latestItem.get() != null) {
                        Integer item = latestItem.getAndSet(null);
                        if (item != null) {
                            Thread.sleep(50); // Slow consumer
                            consumed.incrementAndGet();
                            System.out.println("  Consumed latest: " + item);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "consumer");

            producer.start();
            consumer.start();
            producer.join();
            consumer.join(3000);

            System.out.printf("  Produced: %d, Consumed: %d, Overwritten: %d%n",
                    produced.get(), consumed.get(), dropped.get());
            System.out.println("  Trade-off: Always have latest, intermediate values lost");
        }
    }

    /**
     * CONCEPT: Reactive Streams demand protocol simulation.
     * Subscriber controls flow by requesting specific numbers of items.
     * This is exactly how Project Reactor and RxJava implement backpressure.
     */
    static class DemandControlledPublisher {
        private final int[] items;
        private int position = 0;
        private ReactiveSubscription subscription;

        DemandControlledPublisher(int count) {
            this.items = new int[count];
            for (int i = 0; i < count; i++) items[i] = i + 1;
        }

        void subscribe(ReactiveSubscriber<Integer> subscriber) {
            this.subscription = new ReactiveSubscription() {
                @Override
                public void request(long n) {
                    // CONCEPT: Publisher only sends what Subscriber requests
                    long toSend = Math.min(n, items.length - position);
                    System.out.printf("  [PUBLISHER] Subscriber requested %d, sending %d%n", n, toSend);

                    for (int i = 0; i < toSend; i++) {
                        if (position >= items.length) { subscriber.onComplete(); return; }
                        subscriber.onNext(items[position++]);
                    }
                    if (position >= items.length) subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    position = items.length; // Stop producing
                    System.out.println("  [PUBLISHER] Subscription cancelled");
                }
            };
            subscriber.onSubscribe(this.subscription);
        }
    }

    static class SlowSubscriber implements ReactiveSubscriber<Integer> {
        private ReactiveSubscription subscription;
        private final int batchSize;
        private int received = 0;

        SlowSubscriber(int batchSize) { this.batchSize = batchSize; }

        @Override
        public void onSubscribe(ReactiveSubscription subscription) {
            this.subscription = subscription;
            System.out.printf("  [SUBSCRIBER] Subscribed. Requesting initial batch of %d%n", batchSize);
            subscription.request(batchSize); // Request first batch
        }

        @Override
        public void onNext(Integer item) {
            received++;
            System.out.printf("  [SUBSCRIBER] Received item %d (total=%d)%n", item, received);
            // After processing each batch, request next batch
            if (received % batchSize == 0) {
                System.out.printf("  [SUBSCRIBER] Batch complete, requesting next %d%n", batchSize);
                subscription.request(batchSize);
            }
        }

        @Override public void onError(Throwable t) { System.err.println("  [SUBSCRIBER] Error: " + t); }
        @Override public void onComplete() { System.out.println("  [SUBSCRIBER] Stream complete! Total: " + received); }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Backpressure Patterns Demo ===");

        BackpressureExperiment experiment = new BackpressureExperiment();
        experiment.demonstrateDrop();
        experiment.demonstrateBuffer();
        experiment.demonstrateLatest();

        System.out.println("\n--- Demand-Controlled Publisher (Reactive Streams Protocol) ---");
        DemandControlledPublisher publisher = new DemandControlledPublisher(15);
        SlowSubscriber subscriber = new SlowSubscriber(3); // Process 3 at a time
        publisher.subscribe(subscriber);

        System.out.println("\n--- Backpressure Summary ---");
        System.out.println("Strategy   | Data Loss | Latency | Use Case");
        System.out.println("DROP       | Yes       | Low     | Sensor readings, live metrics");
        System.out.println("BUFFER     | No        | Medium  | General messaging, important data");
        System.out.println("LATEST     | Yes       | Low     | UI updates, stock tickers");
        System.out.println("ERROR      | No        | Low     | Strict systems, detect overwhelm");
        System.out.println("BLOCK      | No        | High    | Simple synchronous flow control");
        System.out.println("\nKafka natural backpressure: Consumer polls at its own pace.");
        System.out.println("Producer is never blocked - messages queue in Kafka (within retention).");
    }
}
