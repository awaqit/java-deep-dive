package com.deepdive.month06.week23;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Week 23: Staff Engineer Design Patterns
 *
 * CONCEPT: At the Staff Engineer level, you're expected to drive architectural decisions,
 * manage system migrations, and protect system boundaries. These patterns address
 * those higher-level concerns.
 *
 * Patterns covered:
 *
 * 1. BULKHEAD PATTERN
 *    Isolate failures between subsystems using separate thread pools / connection pools.
 *    From ship design: watertight compartments prevent one hole from sinking the whole ship.
 *    Used by: Netflix Hystrix, Resilience4j bulkhead, cloud service thread isolation.
 *
 * 2. STRANGLER FIG PATTERN
 *    Incrementally replace a legacy system by routing traffic to new implementation.
 *    Named after the fig tree that grows around and eventually replaces the host tree.
 *    Used for: monolith-to-microservices migrations, legacy API rewrites.
 *
 * 3. ANTI-CORRUPTION LAYER (ACL)
 *    Translate between your domain model and an external/legacy system's model.
 *    Prevents external model concepts from "corrupting" your clean domain model.
 *    From Domain-Driven Design (Eric Evans).
 *
 * 4. SIDECAR PATTERN
 *    Attach a helper process to each service for cross-cutting concerns.
 *    Used by: service meshes (Envoy), log collection (Fluentd), mTLS termination.
 *
 * 5. OUTBOX PATTERN
 *    Guarantee at-least-once event publishing by writing to a DB outbox table
 *    in the same transaction as the business operation.
 *    Solves: "dual write" problem (DB write + Kafka publish can't be atomic).
 */
public class DesignPatternsDemo {

    // ==================== 1. BULKHEAD PATTERN ====================

    /**
     * CONCEPT: Bulkhead isolates thread pools per downstream service.
     * If the "payments" service is slow, it fills up its own thread pool.
     * The "inventory" service keeps its own pool and continues working normally.
     *
     * Without bulkhead: slow payments service -> all threads blocked -> entire app unresponsive
     * With bulkhead:    slow payments service -> payments pool full -> only payments degrade
     *
     * Implementation options:
     * - Semaphore bulkhead: limit concurrent calls (lighter weight)
     * - Thread pool bulkhead: separate executor per resource (more isolation)
     */
    static class BulkheadExecutor {
        private final String name;
        private final ExecutorService executor;
        private final Semaphore semaphore;
        private final AtomicInteger rejectedCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);

        BulkheadExecutor(String name, int maxConcurrency, int maxWaitQueue) {
            this.name = name;
            // CONCEPT: Fixed thread pool with bounded queue = hard limit on concurrent work
            this.executor = new ThreadPoolExecutor(
                    maxConcurrency, maxConcurrency,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(maxWaitQueue),
                    r -> new Thread(r, "bulkhead-" + name + "-" + System.nanoTime()),
                    new ThreadPoolExecutor.AbortPolicy() // Reject when full
            );
            this.semaphore = new Semaphore(maxConcurrency + maxWaitQueue);
        }

        <T> CompletableFuture<T> submit(Callable<T> task) {
            if (!semaphore.tryAcquire()) {
                rejectedCount.incrementAndGet();
                return CompletableFuture.failedFuture(
                        new RejectedExecutionException("Bulkhead '" + name + "' is full!"));
            }

            return CompletableFuture.supplyAsync(() -> {
                try {
                    T result = task.call();
                    successCount.incrementAndGet();
                    return result;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    semaphore.release();
                }
            }, executor);
        }

        void printStats() {
            System.out.printf("  Bulkhead '%s': success=%d, rejected=%d%n",
                    name, successCount.get(), rejectedCount.get());
        }

        void shutdown() { executor.shutdownNow(); }
    }

    static void demonstrateBulkhead() throws InterruptedException {
        System.out.println("\n--- Bulkhead Pattern ---");
        System.out.println("Isolated thread pools prevent cascading failures");

        // Separate bulkheads for different downstream services
        BulkheadExecutor paymentsBulkhead   = new BulkheadExecutor("payments", 3, 5);
        BulkheadExecutor inventoryBulkhead  = new BulkheadExecutor("inventory", 5, 10);
        BulkheadExecutor notifyBulkhead     = new BulkheadExecutor("notifications", 2, 3);

        // Simulate: payments service is slow (10ms per call)
        // Inventory service is fast (1ms per call)
        List<CompletableFuture<?>> futures = new ArrayList<>();

        System.out.println("Sending 15 requests to payments (slow) and 15 to inventory (fast)...");

        for (int i = 0; i < 15; i++) {
            final int req = i;
            // Slow payments calls
            futures.add(paymentsBulkhead.submit(() -> {
                Thread.sleep(50); // Slow service
                return "payment-" + req;
            }).exceptionally(e -> {
                System.out.println("  Payment rejected (bulkhead full): request-" + req);
                return null;
            }));

            // Fast inventory calls - should complete fine despite payments being slow
            futures.add(inventoryBulkhead.submit(() -> {
                Thread.sleep(2); // Fast service
                return "inventory-" + req;
            }).exceptionally(e -> {
                System.out.println("  Inventory rejected: request-" + req);
                return null;
            }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(3, TimeUnit.SECONDS)
                .join();

        System.out.println("\nResults:");
        paymentsBulkhead.printStats();
        inventoryBulkhead.printStats();
        notifyBulkhead.printStats();
        System.out.println("  Key insight: inventory stays healthy even when payments is overloaded");

        paymentsBulkhead.shutdown();
        inventoryBulkhead.shutdown();
        notifyBulkhead.shutdown();
    }

    // ==================== 2. STRANGLER FIG PATTERN ====================

    /**
     * CONCEPT: Gradual migration from legacy to new system.
     *
     * Phase 1: New system deployed, 0% traffic (shadow mode)
     * Phase 2: Route X% to new, rest to legacy (canary)
     * Phase 3: Ramp up: 5% -> 10% -> 25% -> 50% -> 100%
     * Phase 4: Legacy decommissioned
     *
     * Benefits:
     * - Zero big-bang migration risk
     * - Rollback = change traffic percentage
     * - Compare results between old and new (shadow testing)
     * - Feature flags control routing (not code deployments)
     *
     * WHY: Big-bang rewrites fail 80%+ of the time (Netscape, Jordan Rules)
     * Strangler Fig = safe, incremental, reversible
     */
    interface OrderService {
        record Order(String id, String userId, double amount, String status) {}
        Order createOrder(String userId, double amount);
        Optional<Order> getOrder(String id);
        List<Order> getOrdersForUser(String userId);
    }

    // Legacy monolith implementation
    static class LegacyOrderService implements OrderService {
        private final Map<String, Order> orders = new ConcurrentHashMap<>();
        private int callCount = 0;

        @Override
        public Order createOrder(String userId, double amount) {
            callCount++;
            String id = "LEGACY-" + callCount;
            Order order = new Order(id, userId, amount, "CREATED");
            orders.put(id, order);
            return order;
        }

        @Override
        public Optional<Order> getOrder(String id) {
            callCount++;
            return Optional.ofNullable(orders.get(id));
        }

        @Override
        public List<Order> getOrdersForUser(String userId) {
            callCount++;
            return orders.values().stream()
                    .filter(o -> o.userId().equals(userId))
                    .toList();
        }

        int getCallCount() { return callCount; }
    }

    // New microservice implementation
    static class NewOrderService implements OrderService {
        private final Map<String, Order> orders = new ConcurrentHashMap<>();
        private int callCount = 0;

        @Override
        public Order createOrder(String userId, double amount) {
            callCount++;
            String id = "NEW-" + UUID.randomUUID().toString().substring(0, 8);
            Order order = new Order(id, userId, amount, "PENDING"); // Different status naming
            orders.put(id, order);
            return order;
        }

        @Override
        public Optional<Order> getOrder(String id) {
            callCount++;
            return Optional.ofNullable(orders.get(id));
        }

        @Override
        public List<Order> getOrdersForUser(String userId) {
            callCount++;
            return orders.values().stream()
                    .filter(o -> o.userId().equals(userId))
                    .toList();
        }

        int getCallCount() { return callCount; }
    }

    /**
     * CONCEPT: Strangler Fig Router / Proxy.
     * Routes a percentage of traffic to the new service.
     * Optionally runs shadow mode (call both, compare, use legacy result).
     */
    static class StranglerFigRouter implements OrderService {
        private final OrderService legacy;
        private final OrderService newService;
        private volatile double newServicePercentage; // 0.0 to 1.0
        private final boolean shadowMode; // Run both, return legacy result
        private final Random random = new Random();

        StranglerFigRouter(OrderService legacy, OrderService newService,
                           double newServicePercentage, boolean shadowMode) {
            this.legacy = legacy;
            this.newService = newService;
            this.newServicePercentage = newServicePercentage;
            this.shadowMode = shadowMode;
        }

        void setTrafficPercentage(double percentage) {
            this.newServicePercentage = Math.max(0, Math.min(1, percentage));
            System.out.printf("  [ROUTER] Traffic migrated: %.0f%% -> new service%n",
                    newServicePercentage * 100);
        }

        private boolean routeToNew() {
            return random.nextDouble() < newServicePercentage;
        }

        @Override
        public Order createOrder(String userId, double amount) {
            if (shadowMode) {
                // Shadow mode: call both, compare, return legacy result
                Order legacyResult = legacy.createOrder(userId, amount);
                try {
                    Order newResult = newService.createOrder(userId, amount);
                    // CONCEPT: Compare results for validation (log differences)
                    if (Math.abs(legacyResult.amount() - newResult.amount()) > 0.01) {
                        System.out.println("  [SHADOW] Result mismatch! Legacy: " +
                                legacyResult + " vs New: " + newResult);
                    }
                } catch (Exception e) {
                    System.out.println("  [SHADOW] New service failed (not user-facing): " + e.getMessage());
                }
                return legacyResult; // Always return legacy in shadow mode
            }

            if (routeToNew()) {
                System.out.println("  [ROUTER -> NEW] createOrder");
                return newService.createOrder(userId, amount);
            }
            System.out.println("  [ROUTER -> LEGACY] createOrder");
            return legacy.createOrder(userId, amount);
        }

        @Override
        public Optional<Order> getOrder(String id) {
            if (routeToNew()) return newService.getOrder(id);
            return legacy.getOrder(id);
        }

        @Override
        public List<Order> getOrdersForUser(String userId) {
            if (routeToNew()) return newService.getOrdersForUser(userId);
            return legacy.getOrdersForUser(userId);
        }
    }

    static void demonstrateStranglerFig() {
        System.out.println("\n--- Strangler Fig Pattern ---");
        System.out.println("Incremental migration from legacy to new service");

        LegacyOrderService legacy = new LegacyOrderService();
        NewOrderService newSvc = new NewOrderService();

        // Phase 1: 0% new service - pure legacy
        StranglerFigRouter router = new StranglerFigRouter(legacy, newSvc, 0.0, false);
        System.out.println("\nPhase 1: 0% traffic to new service");
        for (int i = 0; i < 5; i++) {
            router.createOrder("user-" + i, 100.0 + i);
        }
        System.out.printf("Legacy calls: %d, New calls: %d%n",
                legacy.getCallCount(), newSvc.getCallCount());

        // Phase 2: 25% canary release
        System.out.println("\nPhase 2: 25% canary to new service");
        router.setTrafficPercentage(0.25);
        legacy = new LegacyOrderService();
        newSvc = new NewOrderService();
        router = new StranglerFigRouter(legacy, newSvc, 0.25, false);
        for (int i = 0; i < 20; i++) {
            router.createOrder("user-" + i, 100.0 + i);
        }
        System.out.printf("  After 20 requests: Legacy~%d, New~%d (approx 75/25 split)%n",
                legacy.getCallCount(), newSvc.getCallCount());

        // Phase 3: Shadow mode for validation
        System.out.println("\nPhase 3: Shadow mode (both called, legacy used)");
        StranglerFigRouter shadowRouter = new StranglerFigRouter(legacy, newSvc, 0.0, true);
        shadowRouter.createOrder("user-shadow", 500.0);
        System.out.println("  Shadow: both services called, legacy result returned to user");
    }

    // ==================== 3. ANTI-CORRUPTION LAYER ====================

    /**
     * CONCEPT: Anti-Corruption Layer (ACL) translates between domain models.
     *
     * Problem: External system uses different:
     * - Naming conventions ("cust_id" vs "customerId")
     * - Status codes ("A"/"I" vs "ACTIVE"/"INACTIVE")
     * - Data formats (epoch seconds vs ISO-8601)
     * - Concepts (no "Order" concept, uses "Transaction" + "Shipment" instead)
     *
     * Without ACL: external model leaks into your domain
     *   -> your code has "if legacyStatus == 'A'" everywhere
     *   -> changing external system requires changing all your business logic
     *
     * With ACL: translation happens at one boundary
     *   -> business logic only knows YOUR domain model
     *   -> external system change = update only the ACL
     */

    // === YOUR DOMAIN MODEL (clean) ===
    record Customer(String id, String name, String email, CustomerStatus status) {}
    enum CustomerStatus { ACTIVE, INACTIVE, SUSPENDED }

    record DomainOrder(String orderId, Customer customer, List<OrderItem> items,
                       OrderStatus status, java.time.Instant createdAt) {}
    record OrderItem(String productId, String productName, int quantity, double unitPrice) {}
    enum OrderStatus { DRAFT, SUBMITTED, PROCESSING, SHIPPED, DELIVERED, CANCELLED }

    // === LEGACY EXTERNAL SYSTEM MODEL (messy) ===
    // CONCEPT: This is what a typical legacy system or third-party API looks like
    record LegacyCustomerRecord(
            String cust_id,          // underscore naming
            String full_nm,          // abbreviated field names
            String email_addr,
            String status_cd,        // "A" = active, "I" = inactive, "S" = suspended
            long created_ts          // epoch seconds
    ) {}

    record LegacyOrderRecord(
            String order_num,
            String cust_id,
            String order_dt,         // "YYYYMMDD" format (really!)
            String order_stat,       // "N"=new, "P"=processing, "S"=shipped, "D"=delivered, "X"=cancelled
            double total_amt,
            List<LegacyLineItem> line_items
    ) {}

    record LegacyLineItem(
            String item_cd,
            String item_desc,
            int qty,
            double unit_price
    ) {}

    /**
     * CONCEPT: The Anti-Corruption Layer.
     * All translation logic is in ONE place.
     * Business logic never sees "A", "I", "N", "P" status codes.
     * Business logic never parses "YYYYMMDD" dates.
     */
    static class LegacySystemAcl {
        // CONCEPT: translate() = from external -> your domain
        Customer translateCustomer(LegacyCustomerRecord legacy) {
            return new Customer(
                    legacy.cust_id(),
                    legacy.full_nm(),
                    legacy.email_addr(),
                    translateCustomerStatus(legacy.status_cd())
            );
        }

        CustomerStatus translateCustomerStatus(String legacyCode) {
            return switch (legacyCode) {
                case "A" -> CustomerStatus.ACTIVE;
                case "I" -> CustomerStatus.INACTIVE;
                case "S" -> CustomerStatus.SUSPENDED;
                default  -> throw new IllegalArgumentException(
                        "Unknown legacy customer status: " + legacyCode);
            };
        }

        DomainOrder translateOrder(LegacyOrderRecord legacy, Customer customer) {
            List<OrderItem> items = legacy.line_items().stream()
                    .map(li -> new OrderItem(li.item_cd(), li.item_desc(), li.qty(), li.unit_price()))
                    .toList();

            return new DomainOrder(
                    legacy.order_num(),
                    customer,
                    items,
                    translateOrderStatus(legacy.order_stat()),
                    parseLegacyDate(legacy.order_dt())
            );
        }

        OrderStatus translateOrderStatus(String legacyCode) {
            return switch (legacyCode) {
                case "N" -> OrderStatus.SUBMITTED;
                case "P" -> OrderStatus.PROCESSING;
                case "S" -> OrderStatus.SHIPPED;
                case "D" -> OrderStatus.DELIVERED;
                case "X" -> OrderStatus.CANCELLED;
                default  -> throw new IllegalArgumentException(
                        "Unknown legacy order status: " + legacyCode);
            };
        }

        java.time.Instant parseLegacyDate(String yyyymmdd) {
            // Legacy system uses "YYYYMMDD" format
            int year  = Integer.parseInt(yyyymmdd.substring(0, 4));
            int month = Integer.parseInt(yyyymmdd.substring(4, 6));
            int day   = Integer.parseInt(yyyymmdd.substring(6, 8));
            return java.time.LocalDate.of(year, month, day)
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant();
        }

        // CONCEPT: reverse-translate() = from your domain -> external (for writes)
        LegacyOrderRecord translateToLegacy(DomainOrder order) {
            List<LegacyLineItem> legacyItems = order.items().stream()
                    .map(i -> new LegacyLineItem(i.productId(), i.productName(), i.quantity(), i.unitPrice()))
                    .toList();

            double total = order.items().stream()
                    .mapToDouble(i -> i.quantity() * i.unitPrice())
                    .sum();

            java.time.LocalDate date = order.createdAt()
                    .atZone(java.time.ZoneOffset.UTC).toLocalDate();
            String legacyDate = String.format("%04d%02d%02d",
                    date.getYear(), date.getMonthValue(), date.getDayOfMonth());

            return new LegacyOrderRecord(
                    order.orderId(),
                    order.customer().id(),
                    legacyDate,
                    toLegacyStatus(order.status()),
                    total,
                    legacyItems
            );
        }

        String toLegacyStatus(OrderStatus status) {
            return switch (status) {
                case SUBMITTED   -> "N";
                case PROCESSING  -> "P";
                case SHIPPED     -> "S";
                case DELIVERED   -> "D";
                case CANCELLED   -> "X";
                default          -> throw new IllegalArgumentException(
                        "Cannot translate status to legacy: " + status);
            };
        }
    }

    static void demonstrateAntiCorruptionLayer() {
        System.out.println("\n--- Anti-Corruption Layer (ACL) ---");
        System.out.println("Translating between legacy 'ACME v1' model and clean domain model");

        LegacySystemAcl acl = new LegacySystemAcl();

        // Simulate receiving data from legacy system
        LegacyCustomerRecord legacyCust = new LegacyCustomerRecord(
                "CUST-001", "John Doe", "john@example.com", "A", 1700000000L);
        LegacyOrderRecord legacyOrder = new LegacyOrderRecord(
                "ORD-9999", "CUST-001", "20240115", "P", 149.98,
                List.of(
                        new LegacyLineItem("SKU-123", "Widget Pro", 2, 49.99),
                        new LegacyLineItem("SKU-456", "Gadget Plus", 1, 49.99)
                )
        );

        System.out.println("Legacy data:");
        System.out.println("  Customer status_cd: '" + legacyCust.status_cd() +
                "' -> CustomerStatus." + acl.translateCustomerStatus(legacyCust.status_cd()));
        System.out.println("  Order order_stat: '" + legacyOrder.order_stat() +
                "' -> OrderStatus." + acl.translateOrderStatus(legacyOrder.order_stat()));
        System.out.println("  Order date: '" + legacyOrder.order_dt() +
                "' -> " + acl.parseLegacyDate(legacyOrder.order_dt()));

        // Translate to clean domain objects
        Customer customer = acl.translateCustomer(legacyCust);
        DomainOrder order = acl.translateOrder(legacyOrder, customer);

        System.out.println("\nClean domain objects:");
        System.out.println("  Customer: " + customer);
        System.out.println("  Order status: " + order.status());
        System.out.println("  Order items: " + order.items().size() + " items");
        System.out.println("  Created at: " + order.createdAt());

        // Business logic works with clean domain model
        System.out.println("\nBusiness logic (never sees 'A', 'P', 'YYYYMMDD'):");
        if (customer.status() == CustomerStatus.ACTIVE) {
            System.out.println("  Customer is active - proceed with order processing");
        }
        if (order.status() == OrderStatus.PROCESSING) {
            System.out.println("  Order is processing - schedule fulfillment");
        }

        // Translate back for legacy system write
        LegacyOrderRecord updatedLegacy = acl.translateToLegacy(order);
        System.out.println("\nTranslated back to legacy for write: order_stat='" +
                updatedLegacy.order_stat() + "'");
    }

    // ==================== 4. OUTBOX PATTERN ====================

    /**
     * CONCEPT: The Dual Write Problem.
     * Common bug pattern:
     *   1. Write order to database (success)
     *   2. Publish "OrderCreated" event to Kafka (failure / crash)
     *   Result: Order in DB but no event sent -> downstream services never know
     *
     * OR the reverse:
     *   1. Write order to database (failure)
     *   2. Publish "OrderCreated" event to Kafka (success by mistake in catch block)
     *   Result: Event sent but no order in DB -> phantom events
     *
     * SOLUTION: Outbox Pattern
     *   1. In SAME database transaction:
     *      a. Write order to `orders` table
     *      b. Write event to `outbox` table
     *   2. Separate process ("outbox worker") reads from `outbox` and publishes to Kafka
     *   3. Mark events as published in `outbox`
     *
     * Guarantees: If transaction commits, event WILL be published (eventually).
     * Uses Kafka producer idempotence to prevent duplicates on retry.
     */
    record OutboxEvent(
            String id,
            String aggregateType,   // "Order", "Customer", etc.
            String aggregateId,     // The entity ID
            String eventType,       // "OrderCreated", "OrderShipped"
            String payload,         // JSON
            java.time.Instant createdAt,
            boolean published
    ) {}

    static class OrderRepository {
        private final Map<String, String> orders = new ConcurrentHashMap<>();
        private final List<OutboxEvent> outbox = new ArrayList<>();

        // CONCEPT: Transactional outbox write
        synchronized void createOrder(String orderId, String userId, double amount) {
            // Step 1: Save order (in real DB: within a transaction)
            orders.put(orderId, String.format("{\"id\":\"%s\",\"userId\":\"%s\",\"amount\":%.2f}",
                    orderId, userId, amount));

            // Step 2: Write to outbox IN SAME transaction
            outbox.add(new OutboxEvent(
                    UUID.randomUUID().toString(),
                    "Order",
                    orderId,
                    "OrderCreated",
                    String.format("{\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":%.2f}",
                            orderId, userId, amount),
                    java.time.Instant.now(),
                    false
            ));

            System.out.printf("  [DB-TX] Saved order '%s' + outbox event (atomic)%n", orderId);
        }

        synchronized List<OutboxEvent> getUnpublishedEvents() {
            return outbox.stream().filter(e -> !e.published()).toList();
        }

        synchronized void markPublished(String eventId) {
            outbox.replaceAll(e -> e.id().equals(eventId)
                    ? new OutboxEvent(e.id(), e.aggregateType(), e.aggregateId(),
                    e.eventType(), e.payload(), e.createdAt(), true)
                    : e);
        }
    }

    static class OutboxWorker {
        private final OrderRepository repository;
        private final AtomicInteger publishedCount = new AtomicInteger(0);

        OutboxWorker(OrderRepository repository) {
            this.repository = repository;
        }

        // CONCEPT: Outbox worker polls for unpublished events and publishes them
        void processOutbox() {
            List<OutboxEvent> pending = repository.getUnpublishedEvents();
            for (OutboxEvent event : pending) {
                try {
                    publishToKafka(event);
                    repository.markPublished(event.id());
                    publishedCount.incrementAndGet();
                    System.out.printf("  [OUTBOX-WORKER] Published '%s' for %s:%s%n",
                            event.eventType(), event.aggregateType(), event.aggregateId());
                } catch (Exception e) {
                    System.out.println("  [OUTBOX-WORKER] Publish failed, will retry: " + e.getMessage());
                    // Event stays as unpublished, will be retried
                }
            }
        }

        private void publishToKafka(OutboxEvent event) {
            // In production: kafka producer with idempotence=true
            // KafkaProducer.send(new ProducerRecord<>("orders", event.aggregateId(), event.payload()))
            System.out.printf("    -> Kafka topic 'orders': key='%s', value=%s%n",
                    event.aggregateId(), event.payload().substring(0, 40) + "...");
        }

        int getPublishedCount() { return publishedCount.get(); }
    }

    static void demonstrateOutboxPattern() {
        System.out.println("\n--- Outbox Pattern (Guaranteed Event Publishing) ---");
        System.out.println("Solves the dual-write problem: DB write + Kafka publish atomically");

        OrderRepository repo = new OrderRepository();
        OutboxWorker worker = new OutboxWorker(repo);

        // Business operations (writes go to DB + outbox atomically)
        System.out.println("\nCreating orders:");
        repo.createOrder("ORD-001", "user-42", 99.99);
        repo.createOrder("ORD-002", "user-17", 249.00);
        repo.createOrder("ORD-003", "user-99", 15.50);

        System.out.println("\nOutbox worker runs (separate process/thread):");
        worker.processOutbox();

        System.out.println("\nSummary:");
        System.out.printf("  Events published: %d%n", worker.getPublishedCount());
        System.out.printf("  Pending: %d%n", repo.getUnpublishedEvents().size());
        System.out.println("  Guarantee: if DB transaction committed, event WILL be published");
        System.out.println("  Implementations: Debezium CDC, custom polling, transaction log tailing");
    }

    // ==================== MAIN ====================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Staff Engineer Design Patterns Demo ===");

        demonstrateBulkhead();
        demonstrateStranglerFig();
        demonstrateAntiCorruptionLayer();
        demonstrateOutboxPattern();

        System.out.println("\n--- Pattern Decision Guide ---");
        System.out.println("Bulkhead:          Protecting against cascading failures from slow dependencies");
        System.out.println("Strangler Fig:     Migrating monolith -> microservices without big-bang rewrite");
        System.out.println("Anti-Corruption:   Integrating with legacy/external systems without polluting domain");
        System.out.println("Outbox:            Guaranteeing event publishing with DB writes (dual write fix)");
        System.out.println("Sidecar:           Inject cross-cutting concerns (TLS, tracing, auth) via proxy process");
        System.out.println("CQRS + Event Sourcing: High audit requirements, temporal queries, event-driven arch");
    }
}
