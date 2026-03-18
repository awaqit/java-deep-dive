package com.deepdive.month04.week14;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Week 14: Graceful Shutdown for Stateful Applications
 *
 * CONCEPT: When Kubernetes sends SIGTERM to a pod, the application must:
 * 1. Stop accepting new requests (liveness = false, readiness = false)
 * 2. Finish in-flight requests
 * 3. Flush buffers, commit offsets, release locks
 * 4. Close database connections and resource pools
 * 5. Exit with code 0 (success)
 *
 * The window: terminationGracePeriodSeconds (default: 30s)
 * If app doesn't exit in time, K8s sends SIGKILL (force kill, no cleanup).
 *
 * Kubernetes shutdown sequence:
 * 1. Pod enters "Terminating" state
 * 2. K8s sends SIGTERM to PID 1 in container
 * 3. preStop lifecycle hook executes (if configured)
 * 4. K8s removes pod from Service endpoints (stops sending new traffic)
 *    NOTE: There's a race condition! New requests may still arrive for a few seconds
 *          after SIGTERM. Add a preStop sleep to account for this.
 * 5. App finishes in-flight requests
 * 6. terminationGracePeriodSeconds expires -> SIGKILL
 *
 * Best practice preStop hook (avoids race condition):
 *   lifecycle:
 *     preStop:
 *       exec:
 *         command: ["sh", "-c", "sleep 5"]  # Wait for endpoint removal propagation
 *
 * This demo shows a complete graceful shutdown implementation.
 */
public class GracefulShutdownDemo {

    // Application state machine
    enum AppState { STARTING, RUNNING, SHUTTING_DOWN, STOPPED }

    static class ApplicationServer {
        private volatile AppState state = AppState.STARTING;
        private final AtomicInteger activeRequests = new AtomicInteger(0);
        private final AtomicInteger processedRequests = new AtomicInteger(0);
        private final CountDownLatch shutdownLatch = new CountDownLatch(1);
        private ExecutorService requestPool;
        private ScheduledExecutorService scheduler;
        private DatabaseConnectionPool dbPool;

        void start() throws InterruptedException {
            System.out.println("[APP] Starting application server...");

            // Initialize resources
            requestPool = Executors.newFixedThreadPool(10, r -> {
                Thread t = new Thread(r, "request-handler");
                t.setDaemon(false); // Non-daemon: keeps JVM alive during shutdown
                return t;
            });
            scheduler = Executors.newScheduledThreadPool(2);
            dbPool = new DatabaseConnectionPool(5);
            dbPool.initialize();

            // Register shutdown hook - called on SIGTERM, SIGINT, or JVM exit
            // WHY: This is the entry point for graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[SHUTDOWN HOOK] SIGTERM received - starting graceful shutdown");
                try {
                    gracefulShutdown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("[SHUTDOWN HOOK] Interrupted during shutdown!");
                }
            }, "shutdown-hook"));

            state = AppState.RUNNING;
            System.out.println("[APP] Application started! Serving requests...");
        }

        boolean handleRequest(String requestId) {
            // CONCEPT: Check state before accepting - reject if shutting down
            if (state != AppState.RUNNING) {
                System.out.println("[APP] Rejected request " + requestId + " (server is " + state + ")");
                return false;
            }

            activeRequests.incrementAndGet();
            try {
                // Simulate request processing with DB access
                Thread.sleep(200 + (int)(Math.random() * 300)); // 200-500ms
                dbPool.executeQuery("SELECT * FROM orders WHERE id = '" + requestId + "'");
                processedRequests.incrementAndGet();
                System.out.println("[APP] Processed request: " + requestId +
                        " (active=" + activeRequests.get() + ")");
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                activeRequests.decrementAndGet();
            }
        }

        /**
         * CONCEPT: Graceful shutdown sequence.
         * Must complete within terminationGracePeriodSeconds (typically 30s).
         */
        private void gracefulShutdown() throws InterruptedException {
            if (state == AppState.SHUTTING_DOWN || state == AppState.STOPPED) return;
            state = AppState.SHUTTING_DOWN;

            System.out.println("[SHUTDOWN] Phase 1: Stopped accepting new requests");

            // CONCEPT: Wait for preStop hook to complete (endpoint removal propagation)
            // In production: sleep(5000) to account for k8s endpoint propagation delay
            System.out.println("[SHUTDOWN] Phase 2: Waiting for in-flight requests to complete...");
            long shutdownStart = System.currentTimeMillis();
            long timeoutMs = 25_000; // Leave 5s buffer before SIGKILL at 30s

            while (activeRequests.get() > 0) {
                long elapsed = System.currentTimeMillis() - shutdownStart;
                if (elapsed > timeoutMs) {
                    System.err.println("[SHUTDOWN] TIMEOUT: " + activeRequests.get() +
                            " requests still active after " + (elapsed / 1000) + "s - forcing close");
                    break;
                }
                System.out.println("[SHUTDOWN] Waiting for " + activeRequests.get() +
                        " active requests... (" + (elapsed / 1000) + "s elapsed)");
                Thread.sleep(500);
            }

            System.out.println("[SHUTDOWN] Phase 3: Shutting down thread pools...");
            requestPool.shutdown();
            if (!requestPool.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("[SHUTDOWN] Request pool didn't terminate, forcing shutdown");
                requestPool.shutdownNow();
            }

            scheduler.shutdown();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);

            System.out.println("[SHUTDOWN] Phase 4: Closing database connection pool...");
            dbPool.close();

            state = AppState.STOPPED;
            System.out.println("[SHUTDOWN] Graceful shutdown complete! " +
                    processedRequests.get() + " requests processed.");
            shutdownLatch.countDown();
        }

        void awaitShutdown() throws InterruptedException {
            shutdownLatch.await();
        }

        AppState getState() { return state; }
        int getActiveRequests() { return activeRequests.get(); }
    }

    // Simulated database connection pool
    static class DatabaseConnectionPool {
        private final int size;
        private final Semaphore connections;
        private volatile boolean closed = false;

        DatabaseConnectionPool(int size) {
            this.size = size;
            this.connections = new Semaphore(size, true);
        }

        void initialize() {
            System.out.println("[DB] Connection pool initialized with " + size + " connections");
        }

        void executeQuery(String sql) throws InterruptedException {
            if (closed) throw new IllegalStateException("Connection pool is closed");
            connections.acquire();
            try {
                // Simulate DB query
                Thread.sleep(10);
            } finally {
                connections.release();
            }
        }

        void close() {
            closed = true;
            System.out.println("[DB] Connection pool closed. All connections returned.");
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Graceful Shutdown Demo ===");
        System.out.println("Simulates K8s SIGTERM -> graceful shutdown process\n");

        ApplicationServer server = new ApplicationServer();
        server.start();

        // Simulate some in-flight requests
        ExecutorService simulatedClients = Executors.newFixedThreadPool(5);
        for (int i = 0; i < 8; i++) {
            final String reqId = "req-" + i;
            simulatedClients.submit(() -> server.handleRequest(reqId));
            Thread.sleep(100); // Stagger request arrival
        }

        // Simulate SIGTERM after 300ms
        Thread.sleep(300);
        System.out.println("\n[KUBERNETES] Sending SIGTERM to pod...");

        // In real K8s: OS sends SIGTERM to PID 1 -> JVM shutdown hook runs
        // Here we manually trigger it for demo
        simulatedClients.shutdown();

        // Trigger shutdown hook programmatically (simulates JVM receiving SIGTERM)
        Thread shutdownHook = new Thread(() -> {
            System.out.println("[SIMULATED SIGTERM] JVM shutdown initiated");
            System.exit(0); // Triggers registered shutdown hooks
        }, "sigterm-simulator");

        // Wait a bit for requests to be in-flight, then simulate SIGTERM
        Thread.sleep(500);
        System.out.println("\n[DEMO] Active requests at SIGTERM: " + server.getActiveRequests());

        // Manually call graceful shutdown (in real code, Runtime.addShutdownHook handles this)
        server.awaitShutdown();
    }
}
