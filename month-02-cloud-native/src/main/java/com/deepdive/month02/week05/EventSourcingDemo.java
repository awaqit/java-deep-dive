package com.deepdive.month02.week05;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Week 5: Event Sourcing
 *
 * CONCEPT: Instead of storing the current state of an entity,
 * Event Sourcing stores the SEQUENCE OF EVENTS that led to the current state.
 * The current state is derived by replaying all events from the beginning
 * (or from a snapshot + events since the snapshot).
 *
 * Traditional state storage:  { orderId: "X", status: "CONFIRMED", total: 99.99 }
 * Event sourcing storage:     [ OrderCreated, ItemAdded, ItemAdded, OrderConfirmed ]
 *
 * Benefits:
 * - Complete audit log "for free" - every state change is recorded
 * - Time travel: recreate state at any point in time
 * - Event replay: rebuild projections, fix bugs, add new features
 * - Natural fit with CQRS: events trigger read model updates
 * - Traceability: "what happened and why" is always clear
 *
 * Costs:
 * - Snapshots needed for large event streams (performance)
 * - Schema evolution: events are immutable, old events must still be readable
 * - Eventual consistency between event store and projections
 * - Conceptual complexity for teams unfamiliar with the pattern
 *
 * Event stores: EventStoreDB, Axon Framework, Apache Kafka (as event log)
 */
public class EventSourcingDemo {

    // ==================== EVENTS ====================
    // CONCEPT: Events are FACTS - things that happened, named in past tense.
    // They are immutable (records are perfect for this in Java 21).

    sealed interface OrderEvent permits
            EventSourcingDemo.OrderCreated,
            EventSourcingDemo.ItemAdded,
            EventSourcingDemo.ItemRemoved,
            EventSourcingDemo.OrderConfirmed,
            EventSourcingDemo.OrderShipped,
            EventSourcingDemo.OrderCancelled {
        String orderId();
        Instant occurredAt();
        long sequenceNumber();
    }

    record OrderCreated(String orderId, String customerId, Instant occurredAt, long sequenceNumber) implements OrderEvent {}
    record ItemAdded(String orderId, String productId, int quantity, double price, Instant occurredAt, long sequenceNumber) implements OrderEvent {}
    record ItemRemoved(String orderId, String productId, Instant occurredAt, long sequenceNumber) implements OrderEvent {}
    record OrderConfirmed(String orderId, Instant occurredAt, long sequenceNumber) implements OrderEvent {}
    record OrderShipped(String orderId, String trackingNumber, Instant occurredAt, long sequenceNumber) implements OrderEvent {}
    record OrderCancelled(String orderId, String reason, Instant occurredAt, long sequenceNumber) implements OrderEvent {}

    // ==================== EVENT STORE ====================
    // CONCEPT: Append-only log of events. Events are NEVER deleted or modified.

    static class EventStore {
        private final Map<String, List<OrderEvent>> store = new HashMap<>();
        private final List<EventListener> globalListeners = new ArrayList<>();

        interface EventListener {
            void onEvent(OrderEvent event);
        }

        // CONCEPT: Optimistic concurrency control via expected version
        // This prevents two concurrent updates from corrupting state
        void append(String streamId, OrderEvent event, long expectedVersion) {
            List<OrderEvent> stream = store.computeIfAbsent(streamId, k -> new ArrayList<>());
            long actualVersion = stream.size();
            if (actualVersion != expectedVersion) {
                throw new ConcurrentModificationException(
                        "Optimistic concurrency conflict on stream " + streamId +
                        ": expected version " + expectedVersion + " but got " + actualVersion);
            }
            stream.add(event);
            globalListeners.forEach(l -> l.onEvent(event));
        }

        List<OrderEvent> loadStream(String streamId) {
            return Collections.unmodifiableList(store.getOrDefault(streamId, Collections.emptyList()));
        }

        // Load events from a specific version (used with snapshots)
        List<OrderEvent> loadStreamFrom(String streamId, long fromVersion) {
            List<OrderEvent> all = store.getOrDefault(streamId, Collections.emptyList());
            return all.stream()
                    .filter(e -> e.sequenceNumber() >= fromVersion)
                    .toList();
        }

        long currentVersion(String streamId) {
            return store.getOrDefault(streamId, Collections.emptyList()).size();
        }

        void subscribe(EventListener listener) {
            globalListeners.add(listener);
        }
    }

    // ==================== AGGREGATE ====================
    // CONCEPT: The Order aggregate rebuilds its state from events (event replay).

    static class OrderAggregate {
        private String orderId;
        private String customerId;
        private final Map<String, Double> items = new LinkedHashMap<>();
        private String status;
        private long version = 0;

        // Factory: load aggregate from event stream (replay all events)
        static OrderAggregate loadFrom(List<OrderEvent> events) {
            OrderAggregate order = new OrderAggregate();
            for (OrderEvent event : events) {
                order.apply(event);
            }
            return order;
        }

        // CONCEPT: apply() is pure - it updates state, no side effects
        // All state transitions happen through apply()
        private void apply(OrderEvent event) {
            switch (event) {
                case OrderCreated e -> {
                    this.orderId = e.orderId();
                    this.customerId = e.customerId();
                    this.status = "DRAFT";
                }
                case ItemAdded e -> items.put(e.productId(), e.price() * e.quantity());
                case ItemRemoved e -> items.remove(e.productId());
                case OrderConfirmed e -> this.status = "CONFIRMED";
                case OrderShipped e -> this.status = "SHIPPED";
                case OrderCancelled e -> this.status = "CANCELLED";
            }
            this.version++;
        }

        // Business methods emit events, then apply them
        OrderEvent createOrder(String orderId, String customerId, EventStore store) {
            OrderCreated event = new OrderCreated(orderId, customerId, Instant.now(), 0);
            store.append(orderId, event, 0);
            apply(event);
            return event;
        }

        OrderEvent addItem(String productId, int quantity, double price, EventStore store) {
            validateStatus("DRAFT", "add item");
            long seq = store.currentVersion(orderId);
            ItemAdded event = new ItemAdded(orderId, productId, quantity, price, Instant.now(), seq);
            store.append(orderId, event, version);
            apply(event);
            return event;
        }

        OrderEvent confirm(EventStore store) {
            validateStatus("DRAFT", "confirm");
            if (items.isEmpty()) throw new IllegalStateException("Cannot confirm empty order");
            long seq = store.currentVersion(orderId);
            OrderConfirmed event = new OrderConfirmed(orderId, Instant.now(), seq);
            store.append(orderId, event, version);
            apply(event);
            return event;
        }

        OrderEvent ship(String trackingNumber, EventStore store) {
            validateStatus("CONFIRMED", "ship");
            long seq = store.currentVersion(orderId);
            OrderShipped event = new OrderShipped(orderId, trackingNumber, Instant.now(), seq);
            store.append(orderId, event, version);
            apply(event);
            return event;
        }

        private void validateStatus(String required, String action) {
            if (!required.equals(status)) {
                throw new IllegalStateException("Cannot " + action + " order in status: " + status);
            }
        }

        double getTotal() { return items.values().stream().mapToDouble(Double::doubleValue).sum(); }
        String getStatus() { return status; }
        String getOrderId() { return orderId; }
        long getVersion() { return version; }
    }

    // ==================== PROJECTION (Read Model) ====================
    // CONCEPT: Projections build read-optimized views from the event stream.
    // They can be rebuilt at any time by replaying all events.

    static class OrderProjection implements EventStore.EventListener {
        private record OrderSummary(String orderId, String customerId, double total, String status, int version) {}
        private final Map<String, OrderSummary> summaries = new HashMap<>();
        private final List<String> auditLog = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(OrderEvent event) {
            // WHY: Each event updates the projection incrementally (no full replay needed)
            switch (event) {
                case OrderCreated e -> {
                    summaries.put(e.orderId(), new OrderSummary(e.orderId(), e.customerId(), 0, "DRAFT", 1));
                    audit("ORDER CREATED", e.orderId(), "customer=" + e.customerId());
                }
                case ItemAdded e -> {
                    summaries.computeIfPresent(e.orderId(), (k, s) ->
                            new OrderSummary(s.orderId(), s.customerId(),
                                    s.total() + e.price() * e.quantity(), s.status(), s.version() + 1));
                    audit("ITEM ADDED", e.orderId(), e.productId() + " qty=" + e.quantity());
                }
                case OrderConfirmed e -> {
                    summaries.computeIfPresent(e.orderId(), (k, s) ->
                            new OrderSummary(s.orderId(), s.customerId(), s.total(), "CONFIRMED", s.version() + 1));
                    audit("ORDER CONFIRMED", e.orderId(), "total=" + summaries.get(e.orderId()).total());
                }
                case OrderShipped e -> {
                    summaries.computeIfPresent(e.orderId(), (k, s) ->
                            new OrderSummary(s.orderId(), s.customerId(), s.total(), "SHIPPED", s.version() + 1));
                    audit("ORDER SHIPPED", e.orderId(), "tracking=" + e.trackingNumber());
                }
                default -> {}
            }
        }

        private void audit(String action, String orderId, String detail) {
            auditLog.add(String.format("[%s] %s | %s | %s", Instant.now(), action, orderId, detail));
        }

        void printProjection() {
            System.out.println("\n  Current Order Projections:");
            summaries.forEach((id, s) ->
                    System.out.printf("    %s | customer=%s | status=%-10s | total=%.2f | v%d%n",
                            s.orderId(), s.customerId(), s.status(), s.total(), s.version()));
        }

        void printAuditLog() {
            System.out.println("\n  Audit Log (complete history):");
            auditLog.forEach(entry -> System.out.println("    " + entry));
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Event Sourcing Demo ===");

        EventStore eventStore = new EventStore();
        OrderProjection projection = new OrderProjection();
        eventStore.subscribe(projection);

        System.out.println("\n--- Building order state via events ---");

        // Create and evolve an order through events
        OrderAggregate order1 = new OrderAggregate();
        order1.createOrder("ORD-001", "CUST-42", eventStore);
        order1.addItem("LAPTOP", 1, 999.99, eventStore);
        order1.addItem("MOUSE", 2, 29.99, eventStore);
        order1.confirm(eventStore);
        order1.ship("TRACK-XYZ-001", eventStore);

        OrderAggregate order2 = new OrderAggregate();
        order2.createOrder("ORD-002", "CUST-99", eventStore);
        order2.addItem("KEYBOARD", 1, 149.99, eventStore);

        projection.printProjection();

        System.out.println("\n--- Time travel: reconstruct ORD-001 state after 2 events ---");
        // CONCEPT: Load only first 2 events to see historical state
        List<OrderEvent> allEvents = eventStore.loadStream("ORD-001");
        OrderAggregate historicalOrder = OrderAggregate.loadFrom(allEvents.subList(0, 2));
        System.out.printf("  Historical state (after 2 events): status=%s, items=%d, total=%.2f%n",
                historicalOrder.getStatus(), 1, historicalOrder.getTotal());

        System.out.println("\n--- Full event stream for ORD-001 ---");
        allEvents.forEach(e -> System.out.printf("  v%d: %s%n",
                e.sequenceNumber(), e.getClass().getSimpleName()));

        projection.printAuditLog();

        System.out.println("\n--- Optimistic Concurrency Check ---");
        try {
            // Try to modify with wrong version (simulates stale update)
            OrderAggregate staleOrder = OrderAggregate.loadFrom(eventStore.loadStream("ORD-002"));
            // Manually trigger wrong version
            eventStore.append("ORD-002",
                    new ItemAdded("ORD-002", "MONITOR", 1, 499.99, Instant.now(), 999),
                    0); // Wrong expected version!
        } catch (ConcurrentModificationException e) {
            System.out.println("  Conflict detected: " + e.getMessage());
        }
    }
}
