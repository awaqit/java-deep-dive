package com.deepdive.month02.week07;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Week 7: Apache Kafka - Producer
 *
 * CONCEPT: Apache Kafka is a distributed event streaming platform.
 * It acts as a durable, high-throughput message log that:
 * - Decouples producers from consumers (time + space decoupling)
 * - Retains messages for configurable duration (days, weeks, forever)
 * - Supports multiple independent consumer groups reading the same topic
 * - Provides ordering guarantees within a partition
 *
 * Kafka Architecture:
 * - Topic: Named category for events (like a table in a database)
 * - Partition: Ordered, immutable sequence of records within a topic
 *   - Enables parallelism: more partitions = more consumers in a group
 *   - Records within a partition are ORDERED by offset
 * - Offset: Sequential ID of a record within a partition
 * - Broker: Kafka server that stores partitions
 * - Producer: Writes records to topics
 * - Consumer Group: Set of consumers that jointly consume a topic
 *   - Each partition assigned to exactly ONE consumer in a group
 * - Replication Factor: How many copies of each partition exist
 *   - replication.factor=3 means 1 leader + 2 followers
 *
 * Producer guarantees (acks setting):
 * - acks=0:  Fire and forget (fastest, may lose data)
 * - acks=1:  Leader acknowledged (may lose if leader fails before replication)
 * - acks=all (-1): All in-sync replicas acknowledged (strongest guarantee)
 *
 * Exactly-Once Semantics (EOS):
 * - enable.idempotence=true: Producer deduplicates retried messages
 * - transactional.id: Cross-partition atomic writes
 *
 * NOTE: This class demonstrates patterns and concepts.
 * To run with real Kafka: start Kafka with docker-compose, uncomment dependencies.
 */
public class KafkaProducerDemo {

    // CONCEPT: Kafka message structure
    record KafkaRecord<K, V>(
            String topic,
            Integer partition,  // null = let Kafka choose via partitioner
            K key,
            V value,
            Map<String, String> headers,
            Instant timestamp
    ) {
        static <K, V> KafkaRecord<K, V> of(String topic, K key, V value) {
            return new KafkaRecord<>(topic, null, key, value, new HashMap<>(), Instant.now());
        }
        static <K, V> KafkaRecord<K, V> of(String topic, int partition, K key, V value) {
            return new KafkaRecord<>(topic, partition, key, value, new HashMap<>(), Instant.now());
        }
    }

    // CONCEPT: Simulated send result
    record RecordMetadata(String topic, int partition, long offset, long timestamp) {}

    /**
     * CONCEPT: Simulated Kafka Producer showing real configuration patterns.
     * Real config would use: new KafkaProducer<>(props) from kafka-clients library.
     */
    static class SimulatedKafkaProducer<K, V> {
        private final Properties config;
        private final Map<String, List<KafkaRecord<K, V>>> topicStore = new HashMap<>();
        private final Map<String, Long> partitionOffsets = new HashMap<>();

        SimulatedKafkaProducer(Properties config) {
            this.config = config;
            System.out.println("Producer created with config:");
            System.out.println("  bootstrap.servers: " + config.getProperty("bootstrap.servers"));
            System.out.println("  acks: " + config.getProperty("acks", "1"));
            System.out.println("  batch.size: " + config.getProperty("batch.size", "16384"));
            System.out.println("  linger.ms: " + config.getProperty("linger.ms", "0"));
            System.out.println("  compression.type: " + config.getProperty("compression.type", "none"));
        }

        // CONCEPT: send() is async - returns a Future for the RecordMetadata
        CompletableFuture<RecordMetadata> send(KafkaRecord<K, V> record) {
            return CompletableFuture.supplyAsync(() -> {
                String topicPartition = record.topic() + "-" + determinePartition(record);
                long offset = partitionOffsets.merge(topicPartition, 1L, Long::sum) - 1;
                topicStore.computeIfAbsent(record.topic(), k -> new ArrayList<>()).add(record);
                return new RecordMetadata(record.topic(),
                        determinePartition(record), offset, record.timestamp().toEpochMilli());
            });
        }

        // CONCEPT: Callback-based send (more common in real code)
        void send(KafkaRecord<K, V> record, java.util.function.BiConsumer<RecordMetadata, Exception> callback) {
            send(record).whenComplete((meta, ex) -> callback.accept(meta, ex));
        }

        // CONCEPT: flush() ensures all buffered records are sent before returning
        void flush() { /* In real Kafka: blocks until all sent */ }

        // Partition selection logic
        private int determinePartition(KafkaRecord<K, V> record) {
            if (record.partition() != null) return record.partition();
            if (record.key() != null) {
                // CONCEPT: Key-based partitioning ensures same key -> same partition -> ORDERED
                return Math.abs(record.key().hashCode() % 3);
            }
            return (int) (System.currentTimeMillis() % 3); // Round-robin for null keys
        }

        List<KafkaRecord<K, V>> getStoredRecords(String topic) {
            return topicStore.getOrDefault(topic, Collections.emptyList());
        }
    }

    // ==================== PRODUCER CONFIGURATION PATTERNS ====================

    /**
     * CONCEPT: High-throughput producer config - batch + compress + linger.
     * Trade: slight latency increase (linger.ms) for much higher throughput.
     */
    static Properties highThroughputConfig() {
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

        // WHY: Batch more records together = fewer network round trips
        props.put("batch.size", "65536");          // 64KB batch (default: 16KB)
        props.put("linger.ms", "20");              // Wait 20ms to fill batch (default: 0)
        props.put("compression.type", "snappy");   // Compress batches (snappy, gzip, lz4, zstd)
        props.put("buffer.memory", "67108864");    // 64MB producer buffer (default: 32MB)

        // Reliability
        props.put("acks", "1");                    // Leader ack only (throughput > durability)
        props.put("retries", "3");
        props.put("retry.backoff.ms", "100");
        return props;
    }

    /**
     * CONCEPT: Reliable producer config - prioritize durability over throughput.
     * Used for financial transactions, critical events.
     */
    static Properties reliableConfig() {
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

        // WHY: acks=all ensures ALL in-sync replicas confirmed the write
        props.put("acks", "all");                  // Maximum durability
        props.put("enable.idempotence", "true");   // Exactly-once: deduplicates retries
        props.put("max.in.flight.requests.per.connection", "5"); // Required for idempotence
        props.put("retries", "Integer.MAX_VALUE"); // Retry forever
        props.put("delivery.timeout.ms", "120000"); // 2 minutes overall delivery timeout

        props.put("compression.type", "lz4");      // Fast compression
        return props;
    }

    /**
     * CONCEPT: Transactional producer - atomic writes across multiple topics.
     * Used for exactly-once processing in Kafka Streams and consume-transform-produce loops.
     */
    static Properties transactionalConfig() {
        Properties props = reliableConfig();
        props.put("transactional.id", "payment-processor-1");  // Unique per producer instance
        // With transactional.id, producer can:
        // beginTransaction(), send(topic1, ...), send(topic2, ...), commitTransaction()
        // or abortTransaction() - atomically across all topics
        return props;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Kafka Producer Demo ===");
        System.out.println("NOTE: Using simulated Kafka. Connect real Kafka for production use.");

        demonstrateBasicSend();
        demonstrateKeyedMessages();
        demonstrateCallbackProducer();
        demonstrateBatchingConcept();
        explainKafkaInternals();
    }

    private static void demonstrateBasicSend() throws Exception {
        System.out.println("\n--- Basic Async Send ---");

        SimulatedKafkaProducer<String, String> producer =
                new SimulatedKafkaProducer<>(highThroughputConfig());

        // Fire-and-forget pattern (highest throughput, may miss errors)
        for (int i = 0; i < 5; i++) {
            KafkaRecord<String, String> record = KafkaRecord.of(
                    "order-events",
                    "order-" + i,
                    "{\"orderId\":\"ORD-" + i + "\",\"type\":\"ORDER_CREATED\"}"
            );
            producer.send(record); // Async - don't wait
        }

        producer.flush();
        System.out.println("Sent 5 records (fire-and-forget)");
        System.out.println("Records in topic: " + producer.getStoredRecords("order-events").size());
    }

    private static void demonstrateKeyedMessages() throws Exception {
        System.out.println("\n--- Key-Based Partitioning ---");
        System.out.println("Same key -> Same partition -> Ordered for that key");

        SimulatedKafkaProducer<String, String> producer =
                new SimulatedKafkaProducer<>(reliableConfig());

        // CONCEPT: Using customerId as key ensures all events for same customer
        // land in the same partition, preserving per-customer ordering
        String[] customerIds = {"CUST-A", "CUST-B", "CUST-A", "CUST-C", "CUST-A"};
        String[] events = {"LOGIN", "LOGIN", "PURCHASE", "LOGIN", "LOGOUT"};

        for (int i = 0; i < customerIds.length; i++) {
            KafkaRecord<String, String> record = KafkaRecord.of(
                    "customer-events", customerIds[i], events[i]
            );
            RecordMetadata meta = producer.send(record).get();
            System.out.printf("  customer=%s event=%-10s -> partition=%d offset=%d%n",
                    customerIds[i], events[i], meta.partition(), meta.offset());
        }
        System.out.println("Note: CUST-A events all go to the same partition (ordering preserved)");
    }

    private static void demonstrateCallbackProducer() throws Exception {
        System.out.println("\n--- Callback-Based Send (with error handling) ---");

        SimulatedKafkaProducer<String, String> producer =
                new SimulatedKafkaProducer<>(reliableConfig());

        CountDownLatch latch = new java.util.concurrent.CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            final int id = i;
            producer.send(
                    KafkaRecord.of("payments", "payment-" + id, "{\"amount\":" + (i * 100) + "}"),
                    (meta, exception) -> {
                        if (exception != null) {
                            // WHY: Always handle send errors! Silent failures cause data loss
                            System.err.println("  FAILED to send payment-" + id + ": " + exception.getMessage());
                        } else {
                            System.out.printf("  Sent payment-%d to %s[%d]@%d%n",
                                    id, meta.topic(), meta.partition(), meta.offset());
                        }
                        latch.countDown();
                    }
            );
        }

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void demonstrateBatchingConcept() {
        System.out.println("\n--- Batching Optimization ---");
        System.out.println("Kafka batches records before sending for efficiency:");
        System.out.println("  batch.size=65536:   Wait until 64KB of records accumulated");
        System.out.println("  linger.ms=20:       OR wait 20ms (whichever comes first)");
        System.out.println("  compression.type=lz4: Compress each batch");
        System.out.println();
        System.out.println("Throughput formula:");
        System.out.println("  throughput ≈ (batch.size / avg_record_size) / linger.ms * 1000 records/sec");
        System.out.println("  With 1KB records, 64KB batch, 20ms linger: ~3200 records/sec per thread");
        System.out.println("  Multiple threads (partitions) multiply throughput linearly");
    }

    private static void explainKafkaInternals() {
        System.out.println("\n--- Kafka Internal Concepts ---");
        System.out.println("Log structure:");
        System.out.println("  Topic 'orders' with 3 partitions:");
        System.out.println("  Partition 0: [offset 0: order-1] [offset 1: order-4] [offset 2: order-7]");
        System.out.println("  Partition 1: [offset 0: order-2] [offset 1: order-5] ...");
        System.out.println("  Partition 2: [offset 0: order-3] [offset 1: order-6] ...");
        System.out.println();
        System.out.println("Consumer group offset tracking:");
        System.out.println("  Group 'order-processor': P0@offset2, P1@offset1, P2@offset1");
        System.out.println("  Group 'analytics':       P0@offset0, P1@offset0, P2@offset0 (independent)");
        System.out.println();
        System.out.println("Retention: messages kept for retention.ms (default 7 days)");
        System.out.println("           OR until retention.bytes exceeded");
        System.out.println("Log compaction: keep only latest value per key (for state topics)");
    }
}
