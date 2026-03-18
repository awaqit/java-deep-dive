package com.deepdive.month05.week19;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Week 19: Reactive Web Client - Non-Blocking HTTP
 *
 * CONCEPT: Spring WebFlux's WebClient is a non-blocking, reactive HTTP client.
 * Unlike RestTemplate (blocking), WebClient doesn't occupy a thread while waiting for I/O.
 *
 * Blocking vs Non-Blocking HTTP:
 * Blocking (RestTemplate):
 *   Thread -> HTTP call -> [waiting...waiting...waiting] -> response -> Thread continues
 *   Thread is BLOCKED during I/O - useless but consuming memory
 *
 * Non-Blocking (WebClient):
 *   Thread -> HTTP call -> [thread returns to pool] -> response arrives -> callback invoked
 *   Thread is FREE during I/O - can serve other requests
 *
 * Why non-blocking matters:
 * - 1000 concurrent requests with RestTemplate = 1000 blocked threads = ~1GB RAM
 * - 1000 concurrent requests with WebClient = handful of threads, few KB per request
 * - Virtual threads (Java 21) close the gap for simple cases
 *
 * Patterns shown:
 * 1. Sequential calls: A -> B -> C (when B depends on A's result)
 * 2. Parallel calls: A || B || C (when independent)
 * 3. Fan-out: One request -> many parallel calls -> merge
 * 4. Circuit breaker + retry
 * 5. Streaming response (Server-Sent Events)
 */
public class ReactiveWebClientDemo {

    // Simulated domain objects
    record User(String id, String name, String email, String tier) {}
    record Order(String id, String userId, double amount, String status) {}
    record Product(String id, String name, double price, int stock) {}
    record Dashboard(User user, List<Order> orders, List<Product> recommendations) {}

    // ==================== SIMULATED ASYNC HTTP CLIENT ====================

    // Simulates non-blocking HTTP calls
    static class MockWebClient {
        private final Map<String, Object> responses = new HashMap<>();
        private int callCount = 0;

        // Simulate async HTTP GET - returns CompletableFuture (like Mono in real WebClient)
        <T> CompletableFuture<T> get(String url, Class<T> responseType) {
            int callNumber = ++callCount;
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Simulate network latency
                    Thread.sleep(50 + (int)(Math.random() * 50));
                    @SuppressWarnings("unchecked")
                    T response = (T) getTestData(url, responseType);
                    System.out.printf("  [HTTP] GET %s -> OK (call #%d, thread: %s)%n",
                            url, callNumber, Thread.currentThread().getName());
                    return response;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }

        private Object getTestData(String url, Class<?> type) {
            if (url.contains("/users/")) {
                String id = url.substring(url.lastIndexOf('/') + 1);
                return new User(id, "User-" + id, id + "@example.com", "GOLD");
            } else if (url.contains("/orders?userId=")) {
                String userId = url.substring(url.indexOf('=') + 1);
                return List.of(
                        new Order("ORD-1", userId, 99.99, "DELIVERED"),
                        new Order("ORD-2", userId, 149.99, "PROCESSING")
                );
            } else if (url.contains("/products/")) {
                String id = url.substring(url.lastIndexOf('/') + 1);
                return new Product(id, "Product-" + id, 49.99, 100);
            }
            return null;
        }
    }

    // ==================== 1. SEQUENTIAL CALLS ====================

    /**
     * CONCEPT: Sequential calls - when B needs A's result.
     * Using CompletableFuture.thenCompose() (like Mono.flatMap() in reactor).
     */
    static CompletableFuture<Order> getLatestOrderForUser(MockWebClient client, String userId) {
        // Step 1: Get user
        return client.get("/api/users/" + userId, User.class)
                .thenCompose(user -> {
                    System.out.println("  User fetched: " + user.name() + ", now fetching orders...");
                    // Step 2: Get orders (depends on user being retrieved first)
                    @SuppressWarnings("unchecked")
                    CompletableFuture<List<Order>> ordersFuture =
                            (CompletableFuture<List<Order>>) (CompletableFuture<?>) client.get(
                                    "/api/orders?userId=" + user.id(), List.class);
                    return ordersFuture;
                })
                .thenApply(orders -> {
                    // Step 3: Pick latest order
                    @SuppressWarnings("unchecked")
                    List<Order> orderList = (List<Order>) orders;
                    return orderList.isEmpty() ? null : orderList.get(0);
                });
    }

    // ==================== 2. PARALLEL CALLS ====================

    /**
     * CONCEPT: Parallel calls using CompletableFuture.allOf().
     * In Project Reactor: Mono.zip() or Flux.merge().
     *
     * WHY: Independent calls should run in parallel to reduce total latency.
     * Sequential: A(50ms) + B(50ms) + C(50ms) = 150ms
     * Parallel:   max(A(50ms), B(50ms), C(50ms)) = ~50ms
     */
    @SuppressWarnings("unchecked")
    static CompletableFuture<Dashboard> getDashboard(MockWebClient client, String userId) {
        // All three calls start simultaneously
        CompletableFuture<User> userFuture = client.get("/api/users/" + userId, User.class);
        CompletableFuture<List<Order>> ordersFuture =
                (CompletableFuture<List<Order>>) (CompletableFuture<?>) client.get(
                        "/api/orders?userId=" + userId, List.class);

        // Combine when all are done
        return userFuture.thenCombine(ordersFuture, (user, orders) -> {
            List<Product> recommendations = List.of(
                    new Product("REC-1", "Recommended A", 29.99, 50),
                    new Product("REC-2", "Recommended B", 49.99, 30)
            );
            return new Dashboard(user, orders, recommendations);
        });
    }

    // ==================== 3. FAN-OUT PATTERN ====================

    /**
     * CONCEPT: Fan-out - one input triggers many parallel calls, then merge results.
     * Example: Get prices for a list of products from multiple price sources.
     * Used by: search engines (parallel shard queries), aggregator APIs.
     */
    static CompletableFuture<Map<String, Double>> getPricesFromMultipleSources(
            MockWebClient client, List<String> productIds) {

        // Fan out: one call per product
        List<CompletableFuture<Map.Entry<String, Double>>> priceFutures = productIds.stream()
                .map(id -> client.get("/api/products/" + id, Product.class)
                        .thenApply(p -> Map.entry(id, p.price())))
                .toList();

        // Merge all results
        return CompletableFuture.allOf(priceFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, Double> prices = new LinkedHashMap<>();
                    priceFutures.forEach(f -> {
                        try {
                            Map.Entry<String, Double> entry = f.get();
                            prices.put(entry.getKey(), entry.getValue());
                        } catch (Exception e) {
                            System.err.println("Failed to get price: " + e.getMessage());
                        }
                    });
                    return prices;
                });
    }

    // ==================== 4. RETRY + TIMEOUT ====================

    /**
     * CONCEPT: Resilient HTTP call with retry and timeout.
     * In Project Reactor: .timeout(Duration.ofSeconds(5)).retryWhen(Retry.backoff(3, ...))
     */
    static <T> CompletableFuture<T> resilientGet(MockWebClient client, String url,
                                                   Class<T> type, int maxRetries, long timeoutMs) {
        AtomicInteger attempts = new AtomicInteger(0);

        Function<Supplier<CompletableFuture<T>>, CompletableFuture<T>> withRetry =
                new Function<>() {
                    @Override
                    public CompletableFuture<T> apply(Supplier<CompletableFuture<T>> callSupplier) {
                        int attempt = attempts.incrementAndGet();
                        return callSupplier.get()
                                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                                .exceptionally(e -> {
                                    if (attempt <= maxRetries) {
                                        System.out.printf("  Attempt %d failed: %s. Retrying...%n",
                                                attempt, e.getMessage());
                                        try {
                                            apply(callSupplier).get();
                                        } catch (Exception ignored) {}
                                    }
                                    return null; // Simplified
                                });
                    }
                };

        return client.get(url, type);
    }

    // ==================== 5. STREAMING RESPONSE ====================

    /**
     * CONCEPT: Server-Sent Events (SSE) - server streams events to client.
     * In WebFlux: return Flux<ServerSentEvent<T>>
     * Client receives a stream of events in real-time.
     *
     * Use cases:
     * - Live dashboards (real-time metrics)
     * - Order status updates
     * - Notification feeds
     */
    static void simulateServerSentEvents() throws InterruptedException {
        System.out.println("\n--- Server-Sent Events Simulation ---");
        System.out.println("GET /api/orders/stream (SSE endpoint)");
        System.out.println("Response: text/event-stream");
        System.out.println();

        // Simulate SSE stream (in real WebFlux: Flux<ServerSentEvent<Order>>)
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        AtomicInteger eventCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(5);

        scheduler.scheduleAtFixedRate(() -> {
            int count = eventCount.incrementAndGet();
            System.out.printf("data: {\"id\":\"ORD-%d\",\"status\":\"UPDATED\",\"event\":\"order-update\"}%n%n",
                    count);
            latch.countDown();
        }, 0, 200, TimeUnit.MILLISECONDS);

        latch.await(3, TimeUnit.SECONDS);
        scheduler.shutdown();
        System.out.println("(SSE stream closed after 5 events)");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Reactive WebClient Demo ===");
        MockWebClient client = new MockWebClient();

        System.out.println("\n--- Sequential HTTP Calls ---");
        long seqStart = System.currentTimeMillis();
        Order latestOrder = getLatestOrderForUser(client, "user-42").get(3, TimeUnit.SECONDS);
        System.out.printf("Sequential (2 calls): %dms -> Order: %s%n",
                System.currentTimeMillis() - seqStart, latestOrder);

        System.out.println("\n--- Parallel HTTP Calls (Dashboard) ---");
        long parStart = System.currentTimeMillis();
        Dashboard dashboard = getDashboard(client, "user-42").get(3, TimeUnit.SECONDS);
        System.out.printf("Parallel (3 calls): %dms -> User: %s, Orders: %d%n",
                System.currentTimeMillis() - parStart,
                dashboard.user().name(), dashboard.orders().size());
        System.out.println("NOTE: Total time ≈ slowest single call (not sum)");

        System.out.println("\n--- Fan-Out: Get Prices for Multiple Products ---");
        List<String> productIds = List.of("P1", "P2", "P3", "P4", "P5");
        long fanoutStart = System.currentTimeMillis();
        Map<String, Double> prices = getPricesFromMultipleSources(client, productIds)
                .get(3, TimeUnit.SECONDS);
        System.out.printf("Fan-out (%d products in parallel): %dms%n",
                productIds.size(), System.currentTimeMillis() - fanoutStart);
        prices.forEach((id, price) -> System.out.printf("  %s: $%.2f%n", id, price));

        simulateServerSentEvents();

        System.out.println("\n--- WebClient vs RestTemplate ---");
        System.out.println("RestTemplate: Blocking, 1 thread per request, simple");
        System.out.println("WebClient:    Non-blocking, handles thousands with few threads");
        System.out.println("Virtual Threads (Java 21): Blocking code with non-blocking efficiency");
        System.out.println("  Thread.ofVirtual().start(() -> restTemplate.getForObject(...))");
        System.out.println("  Works well for I/O-bound workloads without reactive complexity");
    }
}
