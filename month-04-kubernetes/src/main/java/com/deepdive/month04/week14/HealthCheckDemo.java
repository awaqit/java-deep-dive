package com.deepdive.month04.week14;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Week 14: Kubernetes Health Check Patterns
 *
 * CONCEPT: Kubernetes uses three types of probes to manage pod lifecycle:
 *
 * 1. Liveness Probe:  Is the application still alive?
 *    -> If fails: Kubernetes KILLS and RESTARTS the pod
 *    -> Purpose: Detect deadlocks, infinite loops, corrupted state
 *    -> Example: if app is stuck in infinite GC, liveness detects it
 *    -> Start after startupProbe succeeds
 *
 * 2. Readiness Probe: Is the application ready to receive traffic?
 *    -> If fails: Remove pod from Service endpoints (no new traffic)
 *    -> Pod is NOT killed. It may become ready again.
 *    -> Purpose: Avoid sending traffic to pods that are warming up,
 *                loading data, or temporarily overwhelmed
 *    -> Example: During startup, readiness=false until caches are warm
 *
 * 3. Startup Probe: Has the application started successfully?
 *    -> If fails after failureThreshold: Kubernetes kills the pod
 *    -> Purpose: Give slow-starting apps time to initialize
 *               without triggering liveness failures
 *    -> Once startup succeeds, liveness probes kick in
 *
 * Probe types:
 * - HTTP GET:  GET /health returns 200-399 = healthy
 * - TCP Socket: Port is open = healthy
 * - exec command: Exit code 0 = healthy
 * - gRPC:      gRPC health checking protocol
 *
 * Kubernetes probe configuration:
 * livenessProbe:
 *   httpGet:
 *     path: /actuator/health/liveness
 *     port: 8080
 *   initialDelaySeconds: 30
 *   periodSeconds: 10
 *   failureThreshold: 3
 *   timeoutSeconds: 5
 *
 * readinessProbe:
 *   httpGet:
 *     path: /actuator/health/readiness
 *     port: 8080
 *   initialDelaySeconds: 5
 *   periodSeconds: 5
 *   failureThreshold: 3
 *
 * Spring Boot Actuator automatically exposes these endpoints:
 *   GET /actuator/health          -> combined health
 *   GET /actuator/health/liveness -> liveness group
 *   GET /actuator/health/readiness -> readiness group
 */
public class HealthCheckDemo {

    enum HealthStatus { UP, DOWN, OUT_OF_SERVICE, UNKNOWN }

    record HealthDetail(String component, HealthStatus status, String message,
                        Map<String, Object> details) {}

    record HealthResponse(HealthStatus status, List<HealthDetail> components) {
        boolean isHealthy() { return status == HealthStatus.UP; }
    }

    // ==================== HEALTH INDICATORS ====================

    interface HealthIndicator {
        String name();
        HealthDetail check();
    }

    // CONCEPT: Database health check - verify connectivity and query execution
    static class DatabaseHealthIndicator implements HealthIndicator {
        private final AtomicBoolean connectionAvailable = new AtomicBoolean(true);
        private final AtomicLong lastQueryTimeMs = new AtomicLong(5);

        @Override
        public String name() { return "database"; }

        @Override
        public HealthDetail check() {
            try {
                if (!connectionAvailable.get()) {
                    return new HealthDetail(name(), HealthStatus.DOWN,
                            "Cannot connect to database", Map.of());
                }
                long queryTime = lastQueryTimeMs.get();
                if (queryTime > 1000) { // Query taking > 1s = degraded
                    return new HealthDetail(name(), HealthStatus.DOWN,
                            "Database query too slow: " + queryTime + "ms",
                            Map.of("queryTimeMs", queryTime));
                }
                return new HealthDetail(name(), HealthStatus.UP, "Database is healthy",
                        Map.of("queryTimeMs", queryTime, "connections", "8/20", "pool", "HikariCP"));
            } catch (Exception e) {
                return new HealthDetail(name(), HealthStatus.DOWN, e.getMessage(), Map.of());
            }
        }

        void simulateFailure(boolean failed) { connectionAvailable.set(!failed); }
        void setQueryTime(long ms) { lastQueryTimeMs.set(ms); }
    }

    // CONCEPT: Kafka health check
    static class KafkaHealthIndicator implements HealthIndicator {
        private volatile boolean brokerReachable = true;

        @Override public String name() { return "kafka"; }

        @Override
        public HealthDetail check() {
            if (!brokerReachable) {
                return new HealthDetail(name(), HealthStatus.DOWN,
                        "Cannot reach Kafka broker", Map.of());
            }
            return new HealthDetail(name(), HealthStatus.UP, "Kafka broker reachable",
                    Map.of("brokers", "kafka:9092", "consumerLag", "12", "topic", "orders"));
        }

        void simulateDown() { brokerReachable = false; }
        void simulateUp() { brokerReachable = true; }
    }

    // CONCEPT: Memory health check - alert when heap usage is too high
    static class MemoryHealthIndicator implements HealthIndicator {
        private static final double THRESHOLD = 0.90; // 90% heap = degraded

        @Override public String name() { return "memory"; }

        @Override
        public HealthDetail check() {
            Runtime rt = Runtime.getRuntime();
            long total = rt.totalMemory();
            long free = rt.freeMemory();
            long used = total - free;
            double ratio = (double) used / rt.maxMemory();

            Map<String, Object> details = Map.of(
                    "usedMB", used / 1_048_576,
                    "totalMB", total / 1_048_576,
                    "maxMB", rt.maxMemory() / 1_048_576,
                    "usagePercent", String.format("%.1f%%", ratio * 100)
            );

            if (ratio > THRESHOLD) {
                return new HealthDetail(name(), HealthStatus.DOWN,
                        "Heap usage critical: " + String.format("%.1f%%", ratio * 100), details);
            }
            return new HealthDetail(name(), HealthStatus.UP, "Memory usage normal", details);
        }
    }

    // ==================== HEALTH ENDPOINT REGISTRY ====================

    /**
     * CONCEPT: Composite health check aggregates all indicators.
     * Similar to Spring Boot Actuator's CompositeHealthContributor.
     */
    static class HealthCheckRegistry {
        private final List<HealthIndicator> livenessIndicators = new ArrayList<>();
        private final List<HealthIndicator> readinessIndicators = new ArrayList<>();

        // CONCEPT: Liveness = "Am I still alive and not in an unrecoverable state?"
        // Only add indicators here if failure means the pod MUST be restarted
        void addLivenessIndicator(HealthIndicator indicator) {
            livenessIndicators.add(indicator);
        }

        // CONCEPT: Readiness = "Am I ready to serve traffic?"
        // Add all indicators that affect ability to process requests
        void addReadinessIndicator(HealthIndicator indicator) {
            readinessIndicators.add(indicator);
        }

        // GET /actuator/health/liveness
        HealthResponse checkLiveness() {
            return aggregate(livenessIndicators);
        }

        // GET /actuator/health/readiness
        HealthResponse checkReadiness() {
            return aggregate(readinessIndicators);
        }

        // GET /actuator/health (combined)
        HealthResponse checkAll() {
            List<HealthIndicator> all = new ArrayList<>(livenessIndicators);
            readinessIndicators.stream()
                    .filter(r -> livenessIndicators.stream().noneMatch(l -> l.name().equals(r.name())))
                    .forEach(all::add);
            return aggregate(all);
        }

        private HealthResponse aggregate(List<HealthIndicator> indicators) {
            List<HealthDetail> details = indicators.stream()
                    .map(HealthIndicator::check)
                    .toList();

            HealthStatus overall = details.stream()
                    .anyMatch(d -> d.status() == HealthStatus.DOWN) ?
                    HealthStatus.DOWN : HealthStatus.UP;

            return new HealthResponse(overall, details);
        }
    }

    // ==================== STARTUP SEQUENCE ====================

    static class ApplicationWithHealthChecks {
        private final HealthCheckRegistry healthRegistry = new HealthCheckRegistry();
        private final DatabaseHealthIndicator dbHealth = new DatabaseHealthIndicator();
        private final KafkaHealthIndicator kafkaHealth = new KafkaHealthIndicator();
        private final MemoryHealthIndicator memoryHealth = new MemoryHealthIndicator();
        private volatile boolean startupComplete = false;
        private volatile boolean ready = false;

        void configure() {
            // CONCEPT: Only add truly critical checks to liveness
            // (DB failure -> pod restart; Kafka failure -> readiness, not liveness)
            healthRegistry.addLivenessIndicator(memoryHealth);
            // Note: DB liveness only if we can't recover from DB loss without restart

            // CONCEPT: Readiness includes all dependencies
            healthRegistry.addReadinessIndicator(dbHealth);
            healthRegistry.addReadinessIndicator(kafkaHealth);
            healthRegistry.addReadinessIndicator(memoryHealth);
        }

        // Startup probe endpoint
        HealthResponse checkStartup() {
            if (startupComplete) {
                return new HealthResponse(HealthStatus.UP, List.of(
                        new HealthDetail("startup", HealthStatus.UP, "Application started", Map.of())));
            }
            return new HealthResponse(HealthStatus.DOWN, List.of(
                    new HealthDetail("startup", HealthStatus.DOWN, "Still initializing...", Map.of())));
        }

        void simulateStartup() throws InterruptedException {
            System.out.println("  Starting up... (readiness=DOWN during startup)");
            Thread.sleep(200); // Simulate slow startup
            startupComplete = true;
            ready = true;
            System.out.println("  Startup complete! readiness=UP");
        }

        HealthCheckRegistry getRegistry() { return healthRegistry; }
        DatabaseHealthIndicator getDbHealth() { return dbHealth; }
        KafkaHealthIndicator getKafkaHealth() { return kafkaHealth; }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Kubernetes Health Check Patterns ===");

        demonstrateHealthChecks();
        demonstrateFailureScenarios();
        explainProbeConfiguration();
    }

    private static void demonstrateHealthChecks() throws InterruptedException {
        System.out.println("\n--- Application Startup and Health Checks ---");

        ApplicationWithHealthChecks app = new ApplicationWithHealthChecks();
        app.configure();

        // Startup sequence
        System.out.println("GET /actuator/health/startup -> " + app.checkStartup().status());
        app.simulateStartup();
        System.out.println("GET /actuator/health/startup -> " + app.checkStartup().status());

        // Normal operation
        System.out.println("\n[Normal operation]");
        printHealth("GET /actuator/health/liveness", app.getRegistry().checkLiveness());
        printHealth("GET /actuator/health/readiness", app.getRegistry().checkReadiness());
    }

    private static void demonstrateFailureScenarios() throws InterruptedException {
        System.out.println("\n--- Failure Scenarios ---");

        ApplicationWithHealthChecks app = new ApplicationWithHealthChecks();
        app.configure();
        app.simulateStartup();

        // Scenario 1: Database goes down
        System.out.println("\n[Scenario 1: Database failure]");
        app.getDbHealth().simulateFailure(true);
        printHealth("liveness", app.getRegistry().checkLiveness());
        printHealth("readiness", app.getRegistry().checkReadiness());
        System.out.println("-> K8s: Remove pod from Service endpoints (not kill - DB might recover)");
        System.out.println("-> If liveness doesn't include DB: pod stays alive, just not ready");

        // Recover
        app.getDbHealth().simulateFailure(false);
        System.out.println("\n[Database recovered]");
        printHealth("readiness", app.getRegistry().checkReadiness());
        System.out.println("-> K8s: Re-add pod to Service endpoints");

        // Scenario 2: Slow DB queries
        System.out.println("\n[Scenario 2: DB slow queries (>1000ms)]");
        app.getDbHealth().setQueryTime(1500);
        printHealth("readiness", app.getRegistry().checkReadiness());
        app.getDbHealth().setQueryTime(5);

        // Scenario 3: Kafka down (readiness fails, liveness OK)
        System.out.println("\n[Scenario 3: Kafka unavailable]");
        app.getKafkaHealth().simulateDown();
        printHealth("liveness", app.getRegistry().checkLiveness());
        printHealth("readiness", app.getRegistry().checkReadiness());
        System.out.println("-> K8s: Readiness fails, pod removed from load balancer");
        System.out.println("   K8s: Liveness still passes, pod NOT restarted");
        app.getKafkaHealth().simulateUp();
    }

    private static void printHealth(String endpoint, HealthResponse response) {
        System.out.printf("  %s -> %s%n", endpoint, response.status());
        response.components().forEach(c ->
                System.out.printf("    [%s] %-10s %s%n",
                        c.status() == HealthStatus.UP ? "OK" : "FAIL",
                        c.component(), c.message()));
    }

    private static void explainProbeConfiguration() {
        System.out.println("\n--- Recommended Probe Configuration ---");
        System.out.println("Kubernetes YAML:");
        System.out.println();
        System.out.println("startupProbe:");
        System.out.println("  httpGet:");
        System.out.println("    path: /actuator/health/startup");
        System.out.println("    port: 8080");
        System.out.println("  failureThreshold: 30   # 30 * 10s = 5 minute startup budget");
        System.out.println("  periodSeconds: 10");
        System.out.println();
        System.out.println("livenessProbe:");
        System.out.println("  httpGet:");
        System.out.println("    path: /actuator/health/liveness");
        System.out.println("    port: 8080");
        System.out.println("  initialDelaySeconds: 0 # startupProbe handles initial delay");
        System.out.println("  periodSeconds: 10");
        System.out.println("  failureThreshold: 3    # 3 failures = restart");
        System.out.println("  timeoutSeconds: 5");
        System.out.println();
        System.out.println("readinessProbe:");
        System.out.println("  httpGet:");
        System.out.println("    path: /actuator/health/readiness");
        System.out.println("    port: 8080");
        System.out.println("  initialDelaySeconds: 0");
        System.out.println("  periodSeconds: 5       # Check readiness more frequently");
        System.out.println("  failureThreshold: 3");
        System.out.println("  successThreshold: 1");
        System.out.println();
        System.out.println("Common mistake: Using readiness checks in liveness!");
        System.out.println("  If DB is down AND liveness = DOWN -> pod restarts endlessly");
        System.out.println("  But restarting doesn't fix the DB! Use readiness for dependencies.");
    }
}
