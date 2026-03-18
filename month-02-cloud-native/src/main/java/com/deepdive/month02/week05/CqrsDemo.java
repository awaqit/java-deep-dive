package com.deepdive.month02.week05;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Week 5: CQRS - Command Query Responsibility Segregation
 *
 * CONCEPT: CQRS separates the write model (Commands) from the read model (Queries).
 *
 * Why separate?
 * - Read and write workloads have different scalability needs
 *   - Reads: high volume, fast, optimized for query patterns
 *   - Writes: lower volume, consistent, focused on business rules
 * - Read models can be denormalized for specific query patterns
 * - Write models enforce invariants and domain logic
 * - Each side can scale independently
 *
 * CQRS does NOT necessarily mean different databases.
 * Even with one DB, separating command/query handlers improves clarity.
 *
 * Command side:   Validates + applies business rules -> Persists events/state
 * Query side:     Reads optimized projections (read models / view models)
 *
 * Staff Engineer note: CQRS adds complexity. Use it when:
 * - Read/write ratios differ dramatically (e.g., 100:1 reads to writes)
 * - Different teams own read and write sides
 * - Complex domain logic on write side
 * - Multiple specialized read projections needed
 */
public class CqrsDemo {

    // ==================== DOMAIN MODEL ====================

    // CONCEPT: Commands represent intent to change state
    // - Imperative names (CreateOrder, ShipOrder, CancelOrder)
    // - Contain all data needed to execute
    // - Validated before execution
    sealed interface Command permits
            CqrsDemo.CreateOrderCommand, CqrsDemo.AddItemCommand, CqrsDemo.ConfirmOrderCommand {}

    record CreateOrderCommand(String orderId, String customerId, Instant timestamp) implements Command {}
    record AddItemCommand(String orderId, String productId, int quantity, double unitPrice) implements Command {}
    record ConfirmOrderCommand(String orderId, Instant confirmedAt) implements Command {}

    // CONCEPT: Queries represent requests for data (no state change)
    sealed interface Query permits
            CqrsDemo.GetOrderByIdQuery, CqrsDemo.GetOrdersByCustomerQuery, CqrsDemo.GetOrderSummaryQuery {}

    record GetOrderByIdQuery(String orderId) implements Query {}
    record GetOrdersByCustomerQuery(String customerId) implements Query {}
    record GetOrderSummaryQuery(String orderId) implements Query {}

    // CONCEPT: The write-side model (focused on consistency)
    static class Order {
        private final String orderId;
        private final String customerId;
        private final List<OrderItem> items = new ArrayList<>();
        private OrderStatus status;
        private final Instant createdAt;

        Order(String orderId, String customerId, Instant createdAt) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.status = OrderStatus.DRAFT;
            this.createdAt = createdAt;
        }

        void addItem(String productId, int quantity, double unitPrice) {
            if (status != OrderStatus.DRAFT) {
                throw new IllegalStateException("Cannot add items to " + status + " order");
            }
            items.add(new OrderItem(productId, quantity, unitPrice));
        }

        void confirm(Instant confirmedAt) {
            if (status != OrderStatus.DRAFT) {
                throw new IllegalStateException("Cannot confirm " + status + " order");
            }
            if (items.isEmpty()) {
                throw new IllegalStateException("Cannot confirm empty order");
            }
            this.status = OrderStatus.CONFIRMED;
        }

        // Getters
        String getOrderId() { return orderId; }
        String getCustomerId() { return customerId; }
        List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
        OrderStatus getStatus() { return status; }
        Instant getCreatedAt() { return createdAt; }
        double getTotal() { return items.stream().mapToDouble(i -> i.quantity() * i.unitPrice()).sum(); }
    }

    record OrderItem(String productId, int quantity, double unitPrice) {}
    enum OrderStatus { DRAFT, CONFIRMED, SHIPPED, CANCELLED }

    // CONCEPT: Read-side view models (optimized for different query patterns)
    record OrderView(String orderId, String customerId, String status, double total, int itemCount) {}
    record OrderSummary(String orderId, List<String> products, double totalAmount, String status) {}

    // ==================== COMMAND SIDE ====================

    // CONCEPT: CommandHandler - validates and executes commands against write model
    static class OrderCommandHandler {
        private final Map<String, Order> writeStore = new ConcurrentHashMap<>();
        private final List<ReadModelUpdater> readModelUpdaters = new ArrayList<>();

        void addReadModelUpdater(ReadModelUpdater updater) {
            readModelUpdaters.add(updater);
        }

        // WHY: Each command handler has a single responsibility
        void handle(CreateOrderCommand cmd) {
            if (writeStore.containsKey(cmd.orderId())) {
                throw new IllegalArgumentException("Order already exists: " + cmd.orderId());
            }
            Order order = new Order(cmd.orderId(), cmd.customerId(), cmd.timestamp());
            writeStore.put(cmd.orderId(), order);
            notifyReadModels(order); // Update read projections
            System.out.println("  CMD: Created order " + cmd.orderId() + " for customer " + cmd.customerId());
        }

        void handle(AddItemCommand cmd) {
            Order order = getOrThrow(cmd.orderId());
            order.addItem(cmd.productId(), cmd.quantity(), cmd.unitPrice());
            notifyReadModels(order);
            System.out.printf("  CMD: Added %d x %s to order %s (price: %.2f each)%n",
                    cmd.quantity(), cmd.productId(), cmd.orderId(), cmd.unitPrice());
        }

        void handle(ConfirmOrderCommand cmd) {
            Order order = getOrThrow(cmd.orderId());
            order.confirm(cmd.confirmedAt());
            notifyReadModels(order);
            System.out.println("  CMD: Confirmed order " + cmd.orderId() + " total=" + order.getTotal());
        }

        private Order getOrThrow(String orderId) {
            Order order = writeStore.get(orderId);
            if (order == null) throw new NoSuchElementException("Order not found: " + orderId);
            return order;
        }

        private void notifyReadModels(Order order) {
            readModelUpdaters.forEach(u -> u.onOrderUpdated(order));
        }
    }

    // ==================== QUERY SIDE ====================

    // CONCEPT: Read model updater - listens to write-side changes, updates projections
    interface ReadModelUpdater {
        void onOrderUpdated(Order order);
    }

    // CONCEPT: QueryHandler - reads from optimized projections, NEVER touches write model
    static class OrderQueryHandler implements ReadModelUpdater {
        // Denormalized read models - optimized for specific query patterns
        private final Map<String, OrderView> orderViews = new ConcurrentHashMap<>();
        private final Map<String, List<OrderView>> ordersByCustomer = new ConcurrentHashMap<>();
        private final Map<String, OrderSummary> orderSummaries = new ConcurrentHashMap<>();

        @Override
        public void onOrderUpdated(Order order) {
            // Update all read projections synchronously (in production: async via message queue)
            OrderView view = new OrderView(
                    order.getOrderId(), order.getCustomerId(),
                    order.getStatus().name(), order.getTotal(), order.getItems().size()
            );
            orderViews.put(order.getOrderId(), view);

            ordersByCustomer.computeIfAbsent(order.getCustomerId(), k -> new ArrayList<>())
                    .removeIf(v -> v.orderId().equals(order.getOrderId()));
            ordersByCustomer.get(order.getCustomerId()).add(view);

            List<String> products = order.getItems().stream()
                    .map(OrderItem::productId).toList();
            orderSummaries.put(order.getOrderId(),
                    new OrderSummary(order.getOrderId(), products, order.getTotal(), order.getStatus().name()));
        }

        // Query methods - fast reads from denormalized store
        Optional<OrderView> handle(GetOrderByIdQuery query) {
            return Optional.ofNullable(orderViews.get(query.orderId()));
        }

        List<OrderView> handle(GetOrdersByCustomerQuery query) {
            return ordersByCustomer.getOrDefault(query.customerId(), Collections.emptyList());
        }

        Optional<OrderSummary> handle(GetOrderSummaryQuery query) {
            return Optional.ofNullable(orderSummaries.get(query.orderId()));
        }
    }

    public static void main(String[] args) {
        System.out.println("=== CQRS Pattern Demo ===");

        OrderQueryHandler queryHandler = new OrderQueryHandler();
        OrderCommandHandler commandHandler = new OrderCommandHandler();
        commandHandler.addReadModelUpdater(queryHandler);

        System.out.println("\n--- Issuing Commands (Write Side) ---");
        // Commands (state changes)
        commandHandler.handle(new CreateOrderCommand("ORD-001", "CUST-42", Instant.now()));
        commandHandler.handle(new AddItemCommand("ORD-001", "PROD-A", 2, 29.99));
        commandHandler.handle(new AddItemCommand("ORD-001", "PROD-B", 1, 99.99));
        commandHandler.handle(new ConfirmOrderCommand("ORD-001", Instant.now()));

        commandHandler.handle(new CreateOrderCommand("ORD-002", "CUST-42", Instant.now()));
        commandHandler.handle(new AddItemCommand("ORD-002", "PROD-C", 3, 15.00));

        System.out.println("\n--- Issuing Queries (Read Side) ---");
        // Queries (read optimized projections)
        queryHandler.handle(new GetOrderByIdQuery("ORD-001"))
                .ifPresent(v -> System.out.printf("  Order view: %s | status=%s | total=%.2f | items=%d%n",
                        v.orderId(), v.status(), v.total(), v.itemCount()));

        queryHandler.handle(new GetOrdersByCustomerQuery("CUST-42"))
                .forEach(v -> System.out.printf("  Customer order: %s (%s) = %.2f%n",
                        v.orderId(), v.status(), v.total()));

        queryHandler.handle(new GetOrderSummaryQuery("ORD-001"))
                .ifPresent(s -> System.out.printf("  Summary: %s products=%s total=%.2f%n",
                        s.orderId(), s.products(), s.totalAmount()));

        System.out.println("\nKey benefit: Queries use pre-built projections (no joins, fast reads)");
        System.out.println("             Commands enforce domain rules independently");
    }
}
