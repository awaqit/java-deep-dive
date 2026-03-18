package com.deepdive.month03.week12;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Week 12: Load Balancer Algorithms
 *
 * CONCEPT: A load balancer distributes incoming requests across multiple backend servers.
 * Different algorithms optimize for different objectives:
 *
 * 1. Round Robin:         Distribute equally regardless of server capacity
 * 2. Weighted Round Robin: Distribute proportionally to server capacity
 * 3. Least Connections:   Route to server with fewest active connections
 * 4. Least Response Time: Route to fastest server (latency-aware)
 * 5. IP Hash:            Same client IP always routes to same server (session affinity)
 * 6. Random:             Random selection (simple, surprisingly effective with many servers)
 * 7. Power of Two Choices: Pick 2 random servers, choose better one (approximates least-conn)
 *
 * Health checks:
 * - Active: Probe backend periodically (GET /health returns 200)
 * - Passive: Monitor error rates from real traffic
 *
 * Load balancer layers:
 * - L4 (Transport): Route based on TCP/UDP (IP:port). Faster, less context.
 * - L7 (Application): Route based on HTTP headers, URLs, cookies. More intelligent.
 *   L7 enables: content-based routing, A/B testing, authentication offloading
 */
public class LoadBalancerDemo {

    // ==================== SERVER MODEL ====================

    static class Server {
        final String id;
        final int weight;
        volatile boolean healthy = true;
        final AtomicInteger activeConnections = new AtomicInteger(0);
        final AtomicInteger totalRequests = new AtomicInteger(0);
        final AtomicLong totalResponseTimeMs = new AtomicLong(0);
        volatile long lastResponseTimeMs = 0;

        Server(String id, int weight) {
            this.id = id;
            this.weight = weight;
        }

        // Simulate handling a request
        String handleRequest(String request) {
            activeConnections.incrementAndGet();
            totalRequests.incrementAndGet();
            try {
                // Simulate variable processing time (some servers are "slower")
                long processingTime = (long)(Math.random() * 50) + (id.contains("slow") ? 100 : 10);
                Thread.sleep(processingTime);
                lastResponseTimeMs = processingTime;
                totalResponseTimeMs.addAndGet(processingTime);
                return "OK from " + id + " (took " + processingTime + "ms)";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "INTERRUPTED";
            } finally {
                activeConnections.decrementAndGet();
            }
        }

        double avgResponseTimeMs() {
            int reqs = totalRequests.get();
            return reqs > 0 ? (double) totalResponseTimeMs.get() / reqs : 0;
        }

        void printStats() {
            System.out.printf("  %-15s weight=%d requests=%3d activeConn=%d avgTime=%.1fms healthy=%s%n",
                    id, weight, totalRequests.get(), activeConnections.get(),
                    avgResponseTimeMs(), healthy ? "YES" : "NO");
        }
    }

    // ==================== LOAD BALANCER STRATEGIES ====================

    interface LoadBalancerStrategy {
        Optional<Server> select(List<Server> servers, String request);
        String name();
    }

    /**
     * CONCEPT: Round Robin - simple, no state except counter.
     * Works well when all servers have equal capacity.
     */
    static class RoundRobinStrategy implements LoadBalancerStrategy {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Optional<Server> select(List<Server> servers, String request) {
            List<Server> healthy = servers.stream().filter(s -> s.healthy).toList();
            if (healthy.isEmpty()) return Optional.empty();
            int idx = Math.abs(counter.getAndIncrement() % healthy.size());
            return Optional.of(healthy.get(idx));
        }

        @Override public String name() { return "Round Robin"; }
    }

    /**
     * CONCEPT: Weighted Round Robin - server with weight=3 gets 3x the traffic.
     * Useful when servers have different capacities (CPU, memory).
     */
    static class WeightedRoundRobinStrategy implements LoadBalancerStrategy {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Optional<Server> select(List<Server> servers, String request) {
            List<Server> healthy = servers.stream().filter(s -> s.healthy).toList();
            if (healthy.isEmpty()) return Optional.empty();

            // Build weighted list: server with weight=3 appears 3 times
            List<Server> weighted = new ArrayList<>();
            for (Server s : healthy) {
                for (int i = 0; i < s.weight; i++) weighted.add(s);
            }

            int idx = Math.abs(counter.getAndIncrement() % weighted.size());
            return Optional.of(weighted.get(idx));
        }

        @Override public String name() { return "Weighted Round Robin"; }
    }

    /**
     * CONCEPT: Least Connections - route to server with fewest active connections.
     * Better than Round Robin for requests with variable processing time.
     * Used by: HAProxy, NGINX upstream least_conn directive.
     */
    static class LeastConnectionsStrategy implements LoadBalancerStrategy {
        @Override
        public Optional<Server> select(List<Server> servers, String request) {
            return servers.stream()
                    .filter(s -> s.healthy)
                    .min(Comparator.comparingInt(s -> s.activeConnections.get()));
        }

        @Override public String name() { return "Least Connections"; }
    }

    /**
     * CONCEPT: Least Response Time - route to fastest responding server.
     * Tracks exponentially weighted moving average of response times.
     * Used by Nginx Plus, AWS ALB (not available in basic ALB).
     */
    static class LeastResponseTimeStrategy implements LoadBalancerStrategy {
        @Override
        public Optional<Server> select(List<Server> servers, String request) {
            return servers.stream()
                    .filter(s -> s.healthy)
                    .min(Comparator.comparingLong(s -> s.lastResponseTimeMs > 0 ? s.lastResponseTimeMs : Long.MAX_VALUE));
        }

        @Override public String name() { return "Least Response Time"; }
    }

    /**
     * CONCEPT: IP Hash - same client always routes to same server.
     * Enables stateful sessions without external session storage.
     * Downside: poor balance if few clients, breaks session affinity on server addition/removal.
     *
     * WHY: Used for WebSocket connections, file upload continuations, application-level sessions.
     * Better alternative: sticky sessions via cookie (server doesn't need to be rehashed).
     */
    static class IpHashStrategy implements LoadBalancerStrategy {
        @Override
        public Optional<Server> select(List<Server> servers, String clientIp) {
            List<Server> healthy = servers.stream().filter(s -> s.healthy).toList();
            if (healthy.isEmpty()) return Optional.empty();
            int idx = Math.abs(clientIp.hashCode() % healthy.size());
            return Optional.of(healthy.get(idx));
        }

        @Override public String name() { return "IP Hash"; }
    }

    /**
     * CONCEPT: Power of Two Random Choices (P2C)
     * Pick 2 random servers, choose the one with fewer active connections.
     * Approximates Least Connections with O(1) overhead (no scanning all servers).
     *
     * WHY: Used by Nginx, Envoy, Linkerd for cloud-native load balancing.
     * Research shows P2C reduces maximum load by O(log log N) vs Round Robin's O(log N/log log N).
     */
    static class PowerOfTwoChoicesStrategy implements LoadBalancerStrategy {
        private final Random random = new Random();

        @Override
        public Optional<Server> select(List<Server> servers, String request) {
            List<Server> healthy = servers.stream().filter(s -> s.healthy).toList();
            if (healthy.isEmpty()) return Optional.empty();
            if (healthy.size() == 1) return Optional.of(healthy.get(0));

            // Pick 2 random servers, choose better one
            Server server1 = healthy.get(random.nextInt(healthy.size()));
            Server server2 = healthy.get(random.nextInt(healthy.size()));
            // Retry if same server picked twice
            if (server1 == server2 && healthy.size() > 1) {
                server2 = healthy.get((healthy.indexOf(server1) + 1) % healthy.size());
            }

            return Optional.of(server1.activeConnections.get() <= server2.activeConnections.get()
                    ? server1 : server2);
        }

        @Override public String name() { return "Power of Two Choices"; }
    }

    // ==================== LOAD BALANCER ====================

    static class LoadBalancer {
        private final List<Server> servers;
        private final LoadBalancerStrategy strategy;
        private final AtomicInteger totalRequests = new AtomicInteger(0);
        private final AtomicInteger failedRequests = new AtomicInteger(0);

        LoadBalancer(List<Server> servers, LoadBalancerStrategy strategy) {
            this.servers = new CopyOnWriteArrayList<>(servers);
            this.strategy = strategy;
        }

        CompletableFuture<String> sendRequest(String request) {
            return CompletableFuture.supplyAsync(() -> {
                Optional<Server> server = strategy.select(servers, request);
                totalRequests.incrementAndGet();
                if (server.isEmpty()) {
                    failedRequests.incrementAndGet();
                    return "ERROR: No healthy servers";
                }
                return server.get().handleRequest(request);
            });
        }

        void printSummary() {
            System.out.println("Strategy: " + strategy.name());
            System.out.printf("Total requests: %d, Failed (no server): %d%n",
                    totalRequests.get(), failedRequests.get());
            servers.forEach(Server::printStats);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Load Balancer Algorithms Demo ===");

        demonstrateRoundRobin();
        demonstrateWeightedRoundRobin();
        demonstrateLeastConnections();
        demonstratePowerOfTwoChoices();
        demonstrateHealthChecks();
    }

    private static List<Server> createServers() {
        return new ArrayList<>(List.of(
                new Server("server-A", 1),
                new Server("server-B", 1),
                new Server("server-C", 1)
        ));
    }

    private static void runRequests(LoadBalancer lb, int count) throws Exception {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(lb.sendRequest("request-" + i));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
    }

    private static void demonstrateRoundRobin() throws Exception {
        System.out.println("\n--- Round Robin (equal weights) ---");
        List<Server> servers = createServers();
        LoadBalancer lb = new LoadBalancer(servers, new RoundRobinStrategy());
        runRequests(lb, 15);
        lb.printSummary();
    }

    private static void demonstrateWeightedRoundRobin() throws Exception {
        System.out.println("\n--- Weighted Round Robin ---");
        List<Server> servers = new ArrayList<>(List.of(
                new Server("high-capacity", 3),   // 3x capacity
                new Server("medium-capacity", 2), // 2x capacity
                new Server("low-capacity", 1)     // 1x capacity
        ));
        LoadBalancer lb = new LoadBalancer(servers, new WeightedRoundRobinStrategy());
        runRequests(lb, 18); // 18 requests: should distribute 9, 6, 3
        lb.printSummary();
        System.out.println("Expected ratio: 3:2:1 (proportional to weights)");
    }

    private static void demonstrateLeastConnections() throws Exception {
        System.out.println("\n--- Least Connections (variable request duration) ---");
        List<Server> servers = new ArrayList<>(List.of(
                new Server("fast-server", 1),
                new Server("slow-server-X", 1), // "slow" in name triggers longer processing
                new Server("fast-server-2", 1)
        ));
        LoadBalancer lb = new LoadBalancer(servers, new LeastConnectionsStrategy());
        runRequests(lb, 20);
        lb.printSummary();
        System.out.println("Least-conn should send fewer requests to slow server (accumulates connections)");
    }

    private static void demonstratePowerOfTwoChoices() throws Exception {
        System.out.println("\n--- Power of Two Choices (P2C) ---");
        List<Server> servers = createServers();
        LoadBalancer lb = new LoadBalancer(servers, new PowerOfTwoChoicesStrategy());
        runRequests(lb, 20);
        lb.printSummary();
        System.out.println("P2C provides near-optimal load with O(1) complexity vs O(N) for least-conn scan.");
    }

    private static void demonstrateHealthChecks() throws Exception {
        System.out.println("\n--- Health Checks & Failover ---");
        List<Server> servers = createServers();
        LoadBalancer lb = new LoadBalancer(servers, new RoundRobinStrategy());

        // Send some requests normally
        runRequests(lb, 6);
        System.out.println("Before failure:");
        lb.printSummary();

        // Mark one server as unhealthy
        servers.get(1).healthy = false;
        System.out.println("\n** server-B marked UNHEALTHY (e.g., health check /health returned 503) **");

        // Send more requests - should avoid unhealthy server
        runRequests(lb, 9);
        System.out.println("\nAfter failure (server-B excluded):");
        lb.printSummary();

        System.out.println("\nHealth check patterns:");
        System.out.println("  Active: GET /health every 10s, fail after 3 consecutive failures");
        System.out.println("  Passive: Track error rate, mark unhealthy at > 50% errors in 1min");
        System.out.println("  Recovery: Re-add after N successful health checks");
    }
}
