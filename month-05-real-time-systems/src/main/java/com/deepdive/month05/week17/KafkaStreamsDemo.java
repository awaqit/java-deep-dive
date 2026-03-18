package com.deepdive.month05.week17;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Week 17: Kafka Streams - Stateful Stream Processing
 *
 * CONCEPT: Kafka Streams is a client library for building real-time applications
 * that process and transform data stored in Apache Kafka.
 *
 * Key features:
 * - Embedded in your application (no separate cluster needed)
 * - Exactly-once processing semantics
 * - Stateful operations with local state stores (RocksDB backed)
 * - Time-windowed aggregations (tumbling, hopping, sliding windows)
 * - Interactive queries: query state stores directly from your app
 *
 * Processing topologies:
 * Source -> Processor -> Processor -> Sink
 *   |___> branch
 *
 * DSL operations:
 * - filter: Discard records matching predicate
 * - map/mapValues: Transform records
 * - flatMap: One record -> many records
 * - groupBy/groupByKey: Shuffle records for aggregation
 * - aggregate: Stateful accumulation
 * - join: Join two streams or stream + table
 * - windowed operations: Group by time window
 *
 * Windowing types:
 * - Tumbling: Fixed non-overlapping windows [0-10s] [10-20s] [20-30s]
 * - Hopping:  Overlapping windows [0-10s] [5-15s] [10-20s]
 * - Sliding:  Continuous window per event (for joins, within 5 seconds)
 * - Session:  Variable-length inactive periods
 *
 * NOTE: Full Kafka Streams requires kafka-streams dependency (see month-05/build.gradle).
 * This demo shows the patterns and conceptual API.
 */
public class KafkaStreamsDemo {

    // ==================== SIMULATED STREAM PROCESSING ====================

    record OrderEvent(String orderId, String userId, String productId, double amount, long timestamp) {}
    record UserStats(String userId, long orderCount, double totalAmount, double avgAmount) {}
    record ProductRevenue(String productId, double revenue, long count) {}
    record WindowedCount(String key, long count, long windowStart, long windowEnd) {}

    /**
     * CONCEPT: Simulates Kafka Streams KStream - an unbounded sequence of records.
     * Each record is independent (no inherent relationship to other records).
     */
    static class SimulatedKStream<K, V> {
        private List<Map.Entry<K, V>> records = new ArrayList<>();

        static <K, V> SimulatedKStream<K, V> fromList(List<Map.Entry<K, V>> records) {
            SimulatedKStream<K, V> stream = new SimulatedKStream<>();
            stream.records = new ArrayList<>(records);
            return stream;
        }

        // filter: Keep only records matching predicate
        SimulatedKStream<K, V> filter(java.util.function.BiPredicate<K, V> predicate) {
            List<Map.Entry<K, V>> filtered = records.stream()
                    .filter(e -> predicate.test(e.getKey(), e.getValue()))
                    .toList();
            return fromList(filtered);
        }

        // mapValues: Transform values, keep keys
        <W> SimulatedKStream<K, W> mapValues(java.util.function.Function<V, W> mapper) {
            List<Map.Entry<K, W>> mapped = records.stream()
                    .map(e -> Map.entry(e.getKey(), mapper.apply(e.getValue())))
                    .toList();
            return SimulatedKStream.fromList(mapped);
        }

        // map: Transform both key and value
        <KR, VR> SimulatedKStream<KR, VR> map(java.util.function.BiFunction<K, V, Map.Entry<KR, VR>> mapper) {
            List<Map.Entry<KR, VR>> mapped = records.stream()
                    .map(e -> mapper.apply(e.getKey(), e.getValue()))
                    .toList();
            return SimulatedKStream.fromList(mapped);
        }

        // groupByKey: Group for aggregation
        SimulatedGroupedStream<K, V> groupByKey() {
            Map<K, List<V>> grouped = new LinkedHashMap<>();
            for (Map.Entry<K, V> record : records) {
                grouped.computeIfAbsent(record.getKey(), k -> new ArrayList<>()).add(record.getValue());
            }
            return new SimulatedGroupedStream<>(grouped);
        }

        // groupBy: Rekey and group
        <KR> SimulatedGroupedStream<KR, V> groupBy(java.util.function.Function<Map.Entry<K, V>, KR> keyExtractor) {
            Map<KR, List<V>> grouped = new LinkedHashMap<>();
            for (Map.Entry<K, V> record : records) {
                KR newKey = keyExtractor.apply(record);
                grouped.computeIfAbsent(newKey, k -> new ArrayList<>()).add(record.getValue());
            }
            return new SimulatedGroupedStream<>(grouped);
        }

        List<Map.Entry<K, V>> toList() { return Collections.unmodifiableList(records); }
        void print(String name) {
            System.out.println("  Stream [" + name + "] (" + records.size() + " records):");
            records.stream().limit(5).forEach(e ->
                    System.out.println("    " + e.getKey() + " -> " + e.getValue()));
            if (records.size() > 5) System.out.println("    ... " + (records.size() - 5) + " more");
        }
    }

    static class SimulatedGroupedStream<K, V> {
        private final Map<K, List<V>> grouped;

        SimulatedGroupedStream(Map<K, List<V>> grouped) {
            this.grouped = grouped;
        }

        // count: Count records per key -> KTable
        Map<K, Long> count() {
            Map<K, Long> counts = new LinkedHashMap<>();
            grouped.forEach((k, vs) -> counts.put(k, (long) vs.size()));
            return counts;
        }

        // aggregate: General aggregation -> KTable
        <VR> Map<K, VR> aggregate(Supplier<VR> initializer,
                                   java.util.function.BiFunction<V, VR, VR> aggregator) {
            Map<K, VR> result = new LinkedHashMap<>();
            grouped.forEach((k, vs) -> {
                VR acc = initializer.get();
                for (V v : vs) acc = aggregator.apply(v, acc);
                result.put(k, acc);
            });
            return result;
        }

        // Window count: Count per key within time windows
        Map<String, Long> windowedCount(long windowSizeMs) {
            Map<String, Long> windowedCounts = new LinkedHashMap<>();
            grouped.forEach((k, vs) -> {
                // Simplified: group within windows
                windowedCounts.merge(k + "@window", (long) vs.size(), Long::sum);
            });
            return windowedCounts;
        }
    }

    /**
     * CONCEPT: KTable - a changelog stream representing the latest state of a key.
     * Like a materialized view: each key has exactly one latest value.
     * Useful for lookups (product catalog, user profiles).
     */
    static class SimulatedKTable<K, V> {
        private final Map<K, V> table = new LinkedHashMap<>();

        void upsert(K key, V value) { table.put(key, value); }
        Optional<V> lookup(K key) { return Optional.ofNullable(table.get(key)); }
        Map<K, V> asMap() { return Collections.unmodifiableMap(table); }
    }

    public static void main(String[] args) {
        System.out.println("=== Kafka Streams Demo (Simulated) ===");

        List<OrderEvent> orderEvents = generateTestOrders();

        demonstrateFilterAndMap(orderEvents);
        demonstrateAggregation(orderEvents);
        demonstrateWindowedAggregation(orderEvents);
        demonstrateStreamTableJoin(orderEvents);
        explainKafkaStreamsTopology();
    }

    private static List<OrderEvent> generateTestOrders() {
        return List.of(
                new OrderEvent("O1", "user-A", "LAPTOP", 999.99, 1000),
                new OrderEvent("O2", "user-B", "MOUSE", 49.99, 2000),
                new OrderEvent("O3", "user-A", "KEYBOARD", 149.99, 3000),
                new OrderEvent("O4", "user-C", "LAPTOP", 999.99, 4000),
                new OrderEvent("O5", "user-B", "MONITOR", 599.99, 5000),
                new OrderEvent("O6", "user-A", "USB-HUB", 39.99, 6000),
                new OrderEvent("O7", "user-D", "MOUSE", 49.99, 7000),
                new OrderEvent("O8", "user-C", "KEYBOARD", 149.99, 8000),
                new OrderEvent("O9", "user-D", "LAPTOP", 999.99, 9000),
                new OrderEvent("O10", "user-B", "USB-HUB", 39.99, 10000)
        );
    }

    private static void demonstrateFilterAndMap(List<OrderEvent> events) {
        System.out.println("\n--- Filter and Map Operations ---");

        List<Map.Entry<String, OrderEvent>> inputRecords = events.stream()
                .map(e -> Map.entry(e.orderId(), e))
                .toList();

        SimulatedKStream<String, OrderEvent> orderStream = SimulatedKStream.fromList(inputRecords);

        // CONCEPT: Filter high-value orders
        SimulatedKStream<String, OrderEvent> highValueOrders = orderStream
                .filter((key, order) -> order.amount() > 100);
        System.out.println("High-value orders (>$100):");
        highValueOrders.print("high-value");

        // CONCEPT: Map to extract user-keyed records
        SimulatedKStream<String, Double> userAmounts = orderStream
                .map((key, order) -> Map.entry(order.userId(), order.amount()));
        System.out.println("\nRe-keyed by userId:");
        userAmounts.print("by-user");
    }

    private static void demonstrateAggregation(List<OrderEvent> events) {
        System.out.println("\n--- Stateful Aggregation (groupBy + aggregate) ---");

        List<Map.Entry<String, OrderEvent>> inputRecords = events.stream()
                .map(e -> Map.entry(e.orderId(), e))
                .toList();

        SimulatedKStream<String, OrderEvent> orderStream = SimulatedKStream.fromList(inputRecords);

        // CONCEPT: Count orders per user
        Map<String, Long> orderCountByUser = orderStream
                .groupBy(e -> e.getValue().userId())
                .count();

        System.out.println("Order count per user:");
        orderCountByUser.forEach((user, count) ->
                System.out.printf("  %s: %d orders%n", user, count));

        // CONCEPT: Total revenue per product
        Map<String, ProductRevenue> revenueByProduct = orderStream
                .groupBy(e -> e.getValue().productId())
                .aggregate(
                        () -> new ProductRevenue("", 0, 0),
                        (order, acc) -> new ProductRevenue(
                                order.productId(),
                                acc.revenue() + order.amount(),
                                acc.count() + 1)
                );

        System.out.println("\nRevenue by product:");
        revenueByProduct.forEach((product, rev) ->
                System.out.printf("  %-10s $%.2f (%d orders)%n",
                        product, rev.revenue(), rev.count()));

        // CONCEPT: User spending stats
        Map<String, UserStats> userStats = orderStream
                .groupBy(e -> e.getValue().userId())
                .aggregate(
                        () -> new UserStats("", 0, 0, 0),
                        (order, acc) -> {
                            long newCount = acc.orderCount() + 1;
                            double newTotal = acc.totalAmount() + order.amount();
                            return new UserStats(order.userId(), newCount, newTotal, newTotal / newCount);
                        }
                );

        System.out.println("\nUser spending statistics:");
        userStats.forEach((user, stats) ->
                System.out.printf("  %s: %d orders, total=$%.2f, avg=$%.2f%n",
                        user, stats.orderCount(), stats.totalAmount(), stats.avgAmount()));
    }

    private static void demonstrateWindowedAggregation(List<OrderEvent> events) {
        System.out.println("\n--- Windowed Aggregation ---");
        System.out.println("Tumbling window (5s): [0-5s], [5-10s]");

        Map<String, AtomicDouble> windowTotals = new LinkedHashMap<>();

        // Group by 5-second tumbling window
        for (OrderEvent event : events) {
            long windowStart = (event.timestamp() / 5000) * 5000;
            long windowEnd = windowStart + 5000;
            String windowKey = event.userId() + " @ [" + windowStart + "-" + windowEnd + "]";
            windowTotals.computeIfAbsent(windowKey, k -> new AtomicDouble(0))
                    .addAndGet(event.amount());
        }

        windowTotals.forEach((key, total) ->
                System.out.printf("  %s -> $%.2f%n", key, total.get()));

        System.out.println("\nReal Kafka Streams windowed aggregation:");
        System.out.println("  KGroupedStream.windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofSeconds(5)))");
        System.out.println("               .aggregate(initializer, aggregator, materialized)");
    }

    private static void demonstrateStreamTableJoin(List<OrderEvent> events) {
        System.out.println("\n--- Stream-Table Join ---");

        // KTable: user tier lookup (simulates a user profile table)
        SimulatedKTable<String, String> userTierTable = new SimulatedKTable<>();
        userTierTable.upsert("user-A", "GOLD");
        userTierTable.upsert("user-B", "SILVER");
        userTierTable.upsert("user-C", "BRONZE");
        userTierTable.upsert("user-D", "GOLD");

        // CONCEPT: Join orders stream with user tier table
        System.out.println("Orders enriched with user tier:");
        for (OrderEvent order : events) {
            String tier = userTierTable.lookup(order.userId()).orElse("UNKNOWN");
            double discount = switch (tier) {
                case "GOLD" -> 0.10;
                case "SILVER" -> 0.05;
                default -> 0.0;
            };
            double discountedAmount = order.amount() * (1 - discount);
            System.out.printf("  %s | %s (%s) | $%.2f -> $%.2f (%.0f%% discount)%n",
                    order.orderId(), order.userId(), tier, order.amount(), discountedAmount, discount * 100);
        }
    }

    private static void explainKafkaStreamsTopology() {
        System.out.println("\n--- Kafka Streams Topology (Real Code Pattern) ---");
        System.out.println("// Real Kafka Streams DSL:");
        System.out.println("StreamsBuilder builder = new StreamsBuilder();");
        System.out.println();
        System.out.println("KStream<String, OrderEvent> orders = builder.stream(\"orders-topic\",");
        System.out.println("    Consumed.with(Serdes.String(), orderSerde));");
        System.out.println();
        System.out.println("// Count orders per user in 5-minute tumbling windows:");
        System.out.println("orders");
        System.out.println("    .groupBy((key, order) -> order.userId())");
        System.out.println("    .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(5)))");
        System.out.println("    .count(Materialized.as(\"order-counts-store\"))  // RocksDB state store");
        System.out.println("    .toStream()");
        System.out.println("    .to(\"order-counts-topic\");");
        System.out.println();
        System.out.println("// Interactive queries (query state store from REST endpoint):");
        System.out.println("ReadOnlyWindowStore<String, Long> store =");
        System.out.println("    streams.store(StoreQueryParameters.fromNameAndType(");
        System.out.println("        \"order-counts-store\", QueryableStoreTypes.windowStore()));");
        System.out.println("// GET /orders/count?userId=user-A&from=...&to=...");
        System.out.println("WindowStoreIterator<Long> it = store.fetch(\"user-A\", fromTime, toTime);");
    }

    // Helper class for mutable double in lambdas
    static class AtomicDouble {
        private volatile double value;
        AtomicDouble(double value) { this.value = value; }
        synchronized void addAndGet(double delta) { value += delta; }
        double get() { return value; }
    }
}
