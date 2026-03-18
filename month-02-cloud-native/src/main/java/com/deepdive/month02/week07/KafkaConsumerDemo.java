package com.deepdive.month02.week07;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Week 7: Apache Kafka - Consumer
 *
 * CONCEPT: Kafka Consumer reads records from one or more topic partitions.
 * It's the counterpart to the producer.
 *
 * Consumer Group semantics:
 * - Each record is delivered to EXACTLY ONE consumer within a group
 * - Different groups receive ALL records independently
 * - Partitions are assigned to consumers (rebalance on member join/leave)
 *
 * Offset management:
 * - auto.offset.reset:
 *   - "earliest": Start from beginning of topic (useful for reprocessing)
 *   - "latest": Start from newest records (skip historical)
 * - enable.auto.commit=true: Offset committed every auto.commit.interval.ms
 * - enable.auto.commit=false: Manual offset control (exactly-once processing)
 *
 * Rebalancing:
 * When consumers join/leave a group, partitions are redistributed.
 * During rebalance, ALL consumption pauses (stop-the-world).
 * Java 21 + virtual threads: cooperative suspension during rebalance is seamless.
 *
 * Consumer liveness:
 * - heartbeat.interval.ms: How often consumer sends heartbeats to coordinator
 * - session.timeout.ms: If no heartbeat in this period, consumer is removed
 * - max.poll.interval.ms: Max time between poll() calls before consumer is evicted
 *
 * Processing semantics:
 * - At-most-once:   Commit before processing (may lose records if process fails)
 * - At-least-once:  Commit after processing (may reprocess on failure, deduplicate downstream)
 * - Exactly-once:   Transactional Kafka + idempotent processing (most complex, sometimes needed)
 */
public class KafkaConsumerDemo {

    // CONCEPT: Simulated Kafka record received by consumer
    record ConsumerRecord<K, V>(
            String topic,
            int partition,
            long offset,
            K key,
            V value,
            Map<String, String> headers
    ) {}

    record TopicPartition(String topic, int partition) {}

    /**
     * CONCEPT: Simulated Kafka Consumer - mirrors the real KafkaConsumer API.
     * Real usage: new KafkaConsumer<>(props) from kafka-clients library.
     */
    static class SimulatedKafkaConsumer<K, V> {
        private final Properties config;
        private final Map<TopicPartition, Long> committedOffsets = new ConcurrentHashMap<>();
        private final Map<TopicPartition, Long> currentPositions = new ConcurrentHashMap<>();
        private final Map<String, List<ConsumerRecord<K, V>>> topicStore = new HashMap<>();
        private final String groupId;
        private ConsumerRebalanceListener rebalanceListener;

        interface ConsumerRebalanceListener {
            void onPartitionsRevoked(Collection<TopicPartition> partitions);
            void onPartitionsAssigned(Collection<TopicPartition> partitions);
        }

        SimulatedKafkaConsumer(Properties config) {
            this.config = config;
            this.groupId = config.getProperty("group.id");
        }

        void subscribe(List<String> topics, ConsumerRebalanceListener listener) {
            this.rebalanceListener = listener;
            System.out.println("Subscribed to topics: " + topics + " (group: " + groupId + ")");
            // Simulate initial assignment
            for (String topic : topics) {
                for (int p = 0; p < 3; p++) {
                    TopicPartition tp = new TopicPartition(topic, p);
                    currentPositions.put(tp, committedOffsets.getOrDefault(tp, 0L));
                }
            }
            if (listener != null) {
                listener.onPartitionsAssigned(currentPositions.keySet());
            }
        }

        // Add test records to the simulated topic
        void addTestRecords(String topic, List<ConsumerRecord<K, V>> records) {
            topicStore.computeIfAbsent(topic, k -> new ArrayList<>()).addAll(records);
        }

        // Poll returns records up to max.poll.records
        List<ConsumerRecord<K, V>> poll(Duration timeout) {
            int maxRecords = Integer.parseInt(config.getProperty("max.poll.records", "500"));
            List<ConsumerRecord<K, V>> result = new ArrayList<>();

            for (Map.Entry<TopicPartition, Long> entry : currentPositions.entrySet()) {
                TopicPartition tp = entry.getKey();
                long position = entry.getValue();
                List<ConsumerRecord<K, V>> partitionRecords =
                        topicStore.getOrDefault(tp.topic(), Collections.emptyList());

                for (long i = position; i < partitionRecords.size() && result.size() < maxRecords; i++) {
                    ConsumerRecord<K, V> rec = partitionRecords.get((int) i);
                    if (rec.partition() == tp.partition()) {
                        result.add(rec);
                        entry.setValue(i + 1);
                    }
                }
            }
            return result;
        }

        // CONCEPT: Manual commit - commit only after successful processing
        void commitSync() {
            committedOffsets.putAll(currentPositions);
            System.out.println("  Committed offsets: " + currentPositions);
        }

        void commitSync(Map<TopicPartition, Long> offsets) {
            committedOffsets.putAll(offsets);
        }

        void commitAsync(java.util.function.BiConsumer<Map<TopicPartition, Long>, Exception> callback) {
            // Async commit - doesn't block, callback on completion
            Map<TopicPartition, Long> toCommit = new HashMap<>(currentPositions);
            CompletableFuture.runAsync(() -> {
                committedOffsets.putAll(toCommit);
                callback.accept(toCommit, null);
            });
        }

        // Seek to a specific offset (used for reprocessing or error recovery)
        void seek(TopicPartition partition, long offset) {
            currentPositions.put(partition, offset);
            System.out.println("  Seeked " + partition + " to offset " + offset);
        }

        // Pause/resume specific partitions (useful for backpressure)
        void pause(Collection<TopicPartition> partitions) {
            System.out.println("  Paused partitions: " + partitions);
        }

        void resume(Collection<TopicPartition> partitions) {
            System.out.println("  Resumed partitions: " + partitions);
        }

        Map<TopicPartition, Long> getCommittedOffsets() { return Collections.unmodifiableMap(committedOffsets); }
    }

    // ==================== CONSUMER CONFIGURATIONS ====================

    static Properties atLeastOnceConfig() {
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("group.id", "order-processor-v1");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", "false");   // Manual commit = at-least-once control
        props.put("max.poll.records", "100");        // Process 100 records per poll
        props.put("max.poll.interval.ms", "300000"); // 5 min max processing time per batch
        props.put("session.timeout.ms", "45000");    // 45s heartbeat timeout
        props.put("heartbeat.interval.ms", "3000");  // Heartbeat every 3s
        return props;
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Kafka Consumer Demo ===");

        demonstrateAtLeastOnce();
        demonstrateManualOffsetManagement();
        demonstrateRebalanceHandling();
        demonstrateConsumerGroupScaling();
        explainConsumerPatterns();
    }

    /**
     * CONCEPT: At-least-once processing pattern (most common).
     * Commit offsets AFTER processing. If processing fails, records are redelivered.
     * Downstream must handle duplicates (idempotency).
     */
    private static void demonstrateAtLeastOnce() throws InterruptedException {
        System.out.println("\n--- At-Least-Once Processing ---");

        SimulatedKafkaConsumer<String, String> consumer = new SimulatedKafkaConsumer<>(atLeastOnceConfig());

        // Pre-populate topic with test records
        List<ConsumerRecord<String, String>> testRecords = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            testRecords.add(new ConsumerRecord<>("orders", i % 3, i / 3,
                    "order-" + i, "{\"orderId\":\"ORD-" + i + "\",\"amount\":" + (i * 10) + "}", Map.of()));
        }
        consumer.addTestRecords("orders", testRecords);

        consumer.subscribe(List.of("orders"), null);

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicBoolean running = new AtomicBoolean(true);

        Thread consumerThread = new Thread(() -> {
            try {
                while (running.get()) {
                    List<ConsumerRecord<String, String>> records = consumer.poll(Duration.ofMillis(100));
                    if (records.isEmpty()) { running.set(false); break; }

                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            // Process record
                            processRecord(record);
                            processedCount.incrementAndGet();
                        } catch (Exception e) {
                            // WHY: On error, DO NOT commit. Records will be redelivered.
                            System.err.println("  Processing failed for offset " + record.offset() + ": " + e.getMessage());
                            // In production: seek back to failed offset, or send to DLQ
                        }
                    }

                    // CRITICAL: Commit AFTER successful processing, not before
                    consumer.commitSync();
                }
            } catch (Exception e) {
                System.err.println("Consumer error: " + e.getMessage());
            }
        }, "kafka-consumer");

        consumerThread.start();
        consumerThread.join(3000);
        System.out.printf("Processed %d records%n", processedCount.get());
    }

    private static void processRecord(ConsumerRecord<String, String> record) {
        // Simulate record processing
        System.out.printf("  Processing: partition=%d offset=%d key=%s value=%s%n",
                record.partition(), record.offset(), record.key(),
                record.value().substring(0, Math.min(40, record.value().length())));
    }

    /**
     * CONCEPT: Manual offset management for exactly-once processing.
     * Store offsets in the same transaction as the business data
     * (e.g., database transaction stores both result and offset).
     */
    private static void demonstrateManualOffsetManagement() {
        System.out.println("\n--- Manual Offset Management ---");
        System.out.println("Pattern: Store offsets in database, same transaction as processed data");
        System.out.println();
        System.out.println("// Pseudo-code for exactly-once with DB:");
        System.out.println("try (Connection db = dataSource.getConnection()) {");
        System.out.println("  db.setAutoCommit(false);");
        System.out.println("  for (ConsumerRecord r : records) {");
        System.out.println("    processAndInsert(db, r);  // Insert business data");
        System.out.println("    saveOffset(db, r);         // Save offset in SAME transaction");
        System.out.println("  }");
        System.out.println("  db.commit();  // Both data and offset committed atomically");
        System.out.println("  // DON'T call consumer.commitSync() - offset is in DB");
        System.out.println("}");
        System.out.println();
        System.out.println("On restart: consumer.seek() to offsets from DB, not Kafka's committed offsets");
    }

    /**
     * CONCEPT: Rebalance listener - save state before partitions are taken away.
     * Critical for stateful consumers (Kafka Streams, custom aggregations).
     */
    private static void demonstrateRebalanceHandling() {
        System.out.println("\n--- Rebalance Handling ---");

        Map<TopicPartition, Long> pendingOffsets = new HashMap<>();

        SimulatedKafkaConsumer<String, String> consumer = new SimulatedKafkaConsumer<>(atLeastOnceConfig());
        consumer.subscribe(List.of("orders"), new SimulatedKafkaConsumer.ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                // WHY: Commit offsets before partitions are reassigned to another consumer
                // Failure to commit here means the new owner reprocesses these records
                System.out.println("  REBALANCE: Partitions being revoked: " + partitions);
                System.out.println("  REBALANCE: Committing pending offsets before handoff...");
                consumer.commitSync(pendingOffsets);
                pendingOffsets.clear();
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                System.out.println("  REBALANCE: Partitions assigned to this consumer: " + partitions);
                // Optional: seek to specific offsets (e.g., from external DB store)
            }
        });
    }

    /**
     * CONCEPT: Consumer group scaling - adding more consumers to increase throughput.
     * Max parallelism = number of partitions.
     */
    private static void demonstrateConsumerGroupScaling() {
        System.out.println("\n--- Consumer Group Scaling ---");
        System.out.println("Topic: orders (3 partitions)");
        System.out.println();
        System.out.println("1 consumer: handles all 3 partitions (full load)");
        System.out.println("  Consumer-1: P0, P1, P2");
        System.out.println();
        System.out.println("2 consumers: partitions distributed");
        System.out.println("  Consumer-1: P0, P1");
        System.out.println("  Consumer-2: P2");
        System.out.println();
        System.out.println("3 consumers (optimal): 1 partition each");
        System.out.println("  Consumer-1: P0");
        System.out.println("  Consumer-2: P1");
        System.out.println("  Consumer-3: P2");
        System.out.println();
        System.out.println("4+ consumers: extra consumers are idle (no partitions to assign)");
        System.out.println("  Consumer-1: P0");
        System.out.println("  Consumer-2: P1");
        System.out.println("  Consumer-3: P2");
        System.out.println("  Consumer-4: IDLE (no partition available)");
        System.out.println();
        System.out.println("Key insight: To scale beyond 3, increase partition count.");
        System.out.println("  kafka-topics.sh --alter --topic orders --partitions 6");
        System.out.println("  WARNING: Increasing partitions may break key-based ordering!");
    }

    private static void explainConsumerPatterns() {
        System.out.println("\n--- Consumer Patterns Summary ---");
        System.out.println("At-most-once:   commit before process (fastest, potential data loss)");
        System.out.println("At-least-once:  commit after process (common, handle duplicates)");
        System.out.println("Exactly-once:   transactional or idempotent DB write (complex, safest)");
        System.out.println();
        System.out.println("Dead Letter Queue (DLQ) pattern:");
        System.out.println("  On processing failure after N retries -> send to 'orders-dlq' topic");
        System.out.println("  DLQ processor alerts on-call, enables manual inspection/replay");
        System.out.println();
        System.out.println("Consumer lag monitoring (SLI for event processing):");
        System.out.println("  kafka-consumer-groups.sh --describe --group order-processor");
        System.out.println("  Or: JMX metric kafka.consumer:type=consumer-fetch-manager-metrics");
    }
}
