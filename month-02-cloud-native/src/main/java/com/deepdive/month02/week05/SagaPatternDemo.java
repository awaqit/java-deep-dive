package com.deepdive.month02.week05;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Week 5: SAGA Pattern for Distributed Transactions
 *
 * CONCEPT: In microservices, traditional ACID transactions spanning multiple
 * services are impossible (each service has its own database).
 * The SAGA pattern manages distributed transactions through a series of
 * local transactions, each with a compensating transaction for rollback.
 *
 * Two implementations:
 * 1. CHOREOGRAPHY: Services react to events (decentralized)
 *    - Services emit events; other services react
 *    - Pro: Loose coupling, simple for short SAGAs
 *    - Con: Hard to track flow, risk of cyclic dependencies
 *
 * 2. ORCHESTRATION: Central saga orchestrator coordinates
 *    - One "saga coordinator" tells each service what to do
 *    - Pro: Centralized flow control, easy to monitor
 *    - Con: Coupling to orchestrator, single point of failure concern
 *
 * This demo shows ORCHESTRATION pattern with compensation.
 *
 * Example: Order Placement SAGA
 *   Step 1: Create Order
 *   Step 2: Reserve Inventory
 *   Step 3: Charge Payment
 *   Step 4: Schedule Delivery
 *
 *   If Payment fails: compensate Step 2 (release inventory), compensate Step 1 (cancel order)
 */
public class SagaPatternDemo {

    // ==================== SAGA STEP ====================

    // CONCEPT: Each saga step has a forward action and a compensating action
    static class SagaStep {
        private final String name;
        private final SagaAction action;
        private final SagaAction compensation;
        private boolean executed = false;

        @FunctionalInterface
        interface SagaAction {
            SagaResult execute(SagaContext context);
        }

        SagaStep(String name, SagaAction action, SagaAction compensation) {
            this.name = name;
            this.action = action;
            this.compensation = compensation;
        }
    }

    record SagaResult(boolean success, String message, Map<String, Object> data) {
        static SagaResult success(String msg) { return new SagaResult(true, msg, new HashMap<>()); }
        static SagaResult success(String msg, Map<String, Object> data) { return new SagaResult(true, msg, data); }
        static SagaResult failure(String msg) { return new SagaResult(false, msg, new HashMap<>()); }
    }

    // CONCEPT: Shared context passes data between saga steps
    static class SagaContext {
        private final Map<String, Object> data = new HashMap<>();
        private final List<String> log = new ArrayList<>();

        void put(String key, Object value) { data.put(key, value); }
        @SuppressWarnings("unchecked")
        <T> T get(String key) { return (T) data.get(key); }
        void log(String message) {
            log.add(message);
            System.out.println("  [SAGA] " + message);
        }
        List<String> getLog() { return Collections.unmodifiableList(log); }
    }

    // ==================== SAGA ORCHESTRATOR ====================

    enum SagaStatus { RUNNING, COMPLETED, COMPENSATING, FAILED }

    static class SagaOrchestrator {
        private final String sagaId;
        private final List<SagaStep> steps = new ArrayList<>();
        private SagaStatus status = SagaStatus.RUNNING;
        private final SagaContext context = new SagaContext();

        SagaOrchestrator(String sagaId) {
            this.sagaId = sagaId;
        }

        SagaOrchestrator addStep(SagaStep step) {
            steps.add(step);
            return this;
        }

        SagaOrchestrator addStep(String name, SagaStep.SagaAction action, SagaStep.SagaAction compensation) {
            return addStep(new SagaStep(name, action, compensation));
        }

        // CONCEPT: Execute steps sequentially; compensate in reverse if any fails
        boolean execute() {
            context.log("=== SAGA " + sagaId + " STARTED ===");
            int completedSteps = 0;

            for (SagaStep step : steps) {
                context.log("Executing step: " + step.name);
                SagaResult result = step.action.execute(context);
                result.data().forEach(context::put); // Merge step data into context

                if (!result.success()) {
                    context.log("Step FAILED: " + step.name + " | Reason: " + result.message());
                    context.log("Starting COMPENSATION (rollback)...");
                    compensate(completedSteps);
                    return false;
                }

                context.log("Step SUCCEEDED: " + step.name + " | " + result.message());
                step.executed = true;
                completedSteps++;
            }

            status = SagaStatus.COMPLETED;
            context.log("=== SAGA " + sagaId + " COMPLETED SUCCESSFULLY ===");
            return true;
        }

        // CONCEPT: Compensate in reverse order (LIFO - like a stack unwind)
        private void compensate(int lastCompletedIndex) {
            status = SagaStatus.COMPENSATING;
            for (int i = lastCompletedIndex - 1; i >= 0; i--) {
                SagaStep step = steps.get(i);
                if (step.executed) {
                    context.log("Compensating step: " + step.name);
                    SagaResult result = step.compensation.execute(context);
                    context.log("Compensation " + (result.success() ? "succeeded" : "FAILED") +
                            " for: " + step.name + " | " + result.message());
                }
            }
            status = SagaStatus.FAILED;
            context.log("=== SAGA " + sagaId + " FAILED AND COMPENSATED ===");
        }

        SagaStatus getStatus() { return status; }
        SagaContext getContext() { return context; }
    }

    // ==================== SIMULATED SERVICES ====================

    // Simulated microservices with optional failure injection
    static class OrderService {
        private final Map<String, String> orders = new HashMap<>();
        private static final AtomicInteger counter = new AtomicInteger(1);

        SagaResult createOrder(String customerId, List<String> items) {
            String orderId = "ORD-" + counter.getAndIncrement();
            orders.put(orderId, "PENDING:" + customerId);
            return SagaResult.success("Order created: " + orderId, Map.of("orderId", orderId));
        }

        SagaResult cancelOrder(String orderId) {
            orders.remove(orderId);
            return SagaResult.success("Order cancelled: " + orderId);
        }
    }

    static class InventoryService {
        private final Map<String, Integer> stock = new HashMap<>(Map.of("LAPTOP", 5, "MOUSE", 20, "KEYBOARD", 10));
        private final Map<String, String> reservations = new HashMap<>();

        SagaResult reserveInventory(String orderId, String productId, int quantity) {
            int available = stock.getOrDefault(productId, 0);
            if (available < quantity) {
                return SagaResult.failure("Insufficient stock for " + productId + " (available: " + available + ")");
            }
            stock.put(productId, available - quantity);
            reservations.put(orderId, productId + ":" + quantity);
            return SagaResult.success("Reserved " + quantity + "x " + productId);
        }

        SagaResult releaseInventory(String orderId) {
            String reservation = reservations.remove(orderId);
            if (reservation != null) {
                String[] parts = reservation.split(":");
                stock.merge(parts[0], Integer.parseInt(parts[1]), Integer::sum);
                return SagaResult.success("Released reservation for " + orderId);
            }
            return SagaResult.success("No reservation to release for " + orderId);
        }
    }

    static class PaymentService {
        private final boolean shouldFail;
        PaymentService(boolean shouldFail) { this.shouldFail = shouldFail; }

        SagaResult chargePayment(String customerId, double amount) {
            if (shouldFail) {
                return SagaResult.failure("Payment declined for customer " + customerId + " (insufficient funds)");
            }
            String paymentId = "PAY-" + System.currentTimeMillis();
            return SagaResult.success("Charged " + amount + " to " + customerId + " | PaymentId: " + paymentId,
                    Map.of("paymentId", paymentId));
        }

        SagaResult refundPayment(String paymentId) {
            if (paymentId == null) return SagaResult.success("Nothing to refund");
            return SagaResult.success("Refunded payment: " + paymentId);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== SAGA Pattern Demo ===");

        // ---- SCENARIO 1: Happy Path ----
        System.out.println("\n### SCENARIO 1: Successful Order SAGA ###");
        runOrderSaga("CUST-01", "LAPTOP", 1, 999.99, false);

        // ---- SCENARIO 2: Payment Failure (compensation needed) ----
        System.out.println("\n### SCENARIO 2: Failed Order SAGA (payment failure) ###");
        runOrderSaga("CUST-02", "MOUSE", 2, 59.98, true);

        // ---- SCENARIO 3: Inventory Failure ----
        System.out.println("\n### SCENARIO 3: Inventory failure (out of stock) ###");
        runOrderSaga("CUST-03", "DISCONTINUED-ITEM", 1, 0.0, false);
    }

    private static void runOrderSaga(String customerId, String productId,
                                      int quantity, double amount, boolean failPayment) {
        OrderService orderSvc = new OrderService();
        InventoryService inventorySvc = new InventoryService();
        PaymentService paymentSvc = new PaymentService(failPayment);

        SagaOrchestrator saga = new SagaOrchestrator("ORDER-SAGA-" + customerId);

        // Step 1: Create Order
        saga.addStep("CreateOrder",
                ctx -> {
                    List<String> items = List.of(productId);
                    return orderSvc.createOrder(customerId, items);
                },
                ctx -> orderSvc.cancelOrder(ctx.get("orderId"))
        );

        // Step 2: Reserve Inventory
        saga.addStep("ReserveInventory",
                ctx -> inventorySvc.reserveInventory(ctx.get("orderId"), productId, quantity),
                ctx -> inventorySvc.releaseInventory(ctx.get("orderId"))
        );

        // Step 3: Charge Payment
        saga.addStep("ChargePayment",
                ctx -> paymentSvc.chargePayment(customerId, amount),
                ctx -> paymentSvc.refundPayment(ctx.get("paymentId"))
        );

        // Step 4: Schedule Delivery (simple, no compensation needed if pure)
        saga.addStep("ScheduleDelivery",
                ctx -> SagaResult.success("Delivery scheduled for order " + ctx.get("orderId"),
                        Map.of("deliveryId", "DEL-" + System.currentTimeMillis())),
                ctx -> SagaResult.success("Delivery cancelled for " + ctx.get("orderId"))
        );

        boolean success = saga.execute();
        System.out.println("\n  Final SAGA status: " + saga.getStatus() + (success ? " ✓" : " ✗"));
    }
}
