package com.deepdive.month05.week19;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.random.RandomGenerator;

/**
 * Week 19: Observability — Metrics, Tracing, and Structured Logging
 *
 * CONCEPT: Observability is the ability to understand what your system is doing
 * from the outside, using three pillars:
 *   1. Metrics   — aggregated numbers over time (Micrometer → Prometheus → Grafana)
 *   2. Traces    — request journey across services (OpenTelemetry → Jaeger)
 *   3. Logs      — structured events with context (JSON + trace/span IDs → Loki)
 *
 * WHY: As an Engineering Manager or Staff Engineer, observability is the difference
 * between reacting to incidents and preventing them. SLOs, error budgets, and
 * on-call health all depend on good instrumentation. If you can't measure it,
 * you can't manage it — and you can't defend architecture decisions without data.
 *
 * NOTE: This demo simulates Micrometer and OpenTelemetry APIs so it runs without
 * external dependencies. The patterns shown are 1:1 with the real libraries.
 * See SETUP.md for Docker Compose stack (Prometheus + Grafana + Loki + Jaeger).
 *
 * Real dependencies (uncomment in build.gradle):
 *   implementation 'io.micrometer:micrometer-registry-prometheus:1.12.2'
 *   implementation 'io.opentelemetry:opentelemetry-sdk:1.34.1'
 *   implementation 'io.opentelemetry:opentelemetry-exporter-otlp:1.34.1'
 */
public class ObservabilityDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Observability: Metrics, Tracing, and Structured Logging ===\n");

        // Pillar 1 — Metrics with Micrometer
        System.out.println("--- Pillar 1: Metrics (Micrometer → Prometheus) ---");
        MicrometerRegistry registry = new MicrometerRegistry();
        runMetricsDemo(registry);

        // Pillar 2 — Distributed Tracing with OpenTelemetry
        System.out.println("\n--- Pillar 2: Distributed Tracing (OpenTelemetry → Jaeger) ---");
        SimulatedTracer tracer = new SimulatedTracer("order-service");
        runTracingDemo(tracer);

        // Pillar 3 — Structured Logging with trace correlation
        System.out.println("\n--- Pillar 3: Structured Logging (JSON + Trace Correlation) ---");
        runStructuredLoggingDemo(tracer);

        // Real-world scenario: Kafka consumer lag monitoring
        System.out.println("\n--- Real-World: Kafka Consumer Lag Monitoring ---");
        runKafkaLagDemo(registry);

        // SLO / Error Budget
        System.out.println("\n--- SLO and Error Budget Calculation ---");
        runSloDemo(registry);

        // Print simulated Prometheus scrape output
        System.out.println("\n--- Prometheus Scrape Output (/actuator/prometheus) ---");
        System.out.println(registry.scrape());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PILLAR 1 — METRICS
    // CONCEPT: Micrometer is a vendor-neutral metrics facade. You instrument
    // once with Micrometer, then export to Prometheus, Datadog, CloudWatch, etc.
    // WHY: Counters, timers, and gauges answer "how many?", "how long?", "how full?"
    // NOTE: Always add meaningful tags (labels). A counter without tags is nearly useless.
    //       Bad:  counter("http.requests")
    //       Good: counter("http.requests", "method", "POST", "status", "200", "uri", "/orders")
    // ─────────────────────────────────────────────────────────────────────────
    static void runMetricsDemo(MicrometerRegistry registry) throws InterruptedException {
        // Counter — monotonically increasing, never resets
        // WHY: Use for events you want to count: requests, errors, messages processed
        Counter requestCounter = registry.counter("http.requests.total",
                "method", "POST", "uri", "/orders", "status", "200");
        Counter errorCounter = registry.counter("http.requests.total",
                "method", "POST", "uri", "/orders", "status", "500");

        // Timer — measures duration and count together
        // WHY: A single metric gives you throughput (count/sec) AND latency (percentiles)
        Timer requestTimer = registry.timer("http.request.duration", "uri", "/orders");

        // Gauge — snapshot of a current value (can go up or down)
        // WHY: Use for queue depth, active connections, cache size, thread pool usage
        AtomicInteger activeConnections = new AtomicInteger(0);
        registry.gauge("db.connections.active", activeConnections);

        // Simulate traffic
        RandomGenerator rng = RandomGenerator.getDefault();
        for (int i = 0; i < 20; i++) {
            long latencyMs = 20 + rng.nextLong(180);
            requestTimer.record(Duration.ofMillis(latencyMs));
            requestCounter.increment();
            if (rng.nextDouble() < 0.1) errorCounter.increment(); // 10% error rate
            activeConnections.set(rng.nextInt(10, 50));
        }

        System.out.println("Requests processed : " + (long) requestCounter.count());
        System.out.println("Errors             : " + (long) errorCounter.count());
        System.out.printf("Error rate         : %.1f%%%n",
                (errorCounter.count() / requestCounter.count()) * 100);
        System.out.printf("Avg latency        : %.1f ms%n", requestTimer.meanMs());
        System.out.printf("Max latency        : %.1f ms%n", requestTimer.maxMs());
        System.out.println("Active connections : " + activeConnections.get());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PILLAR 2 — DISTRIBUTED TRACING
    // CONCEPT: A trace represents one request's journey across all services.
    // Each service adds a span (start time, end time, metadata). Spans are linked
    // by a shared traceId. OpenTelemetry is the industry standard (merged from
    // OpenTracing + OpenCensus).
    //
    // WHY: When order-service calls payment-service calls fraud-service and
    // something is slow, you need to know WHICH hop is slow. Logs alone can't
    // answer this. Traces can.
    //
    // NOTE: Trace context must be propagated explicitly via HTTP headers:
    //   traceparent: 00-<traceId>-<spanId>-01
    // In Spring Boot + WebClient this is automatic with the OTel agent.
    // ─────────────────────────────────────────────────────────────────────────
    static void runTracingDemo(SimulatedTracer tracer) throws InterruptedException {
        // Simulate: POST /orders → calls payment-service → calls fraud-service
        Span orderSpan = tracer.spanBuilder("POST /orders").startSpan();
        orderSpan.setAttribute("http.method", "POST");
        orderSpan.setAttribute("http.url", "/orders");
        orderSpan.setAttribute("user.id", "usr-42");

        Thread.sleep(12); // order validation

        // Child span — call to payment-service (context propagated via W3C traceparent header)
        Span paymentSpan = tracer.spanBuilder("payment-service/charge")
                .setParent(orderSpan)
                .startSpan();
        paymentSpan.setAttribute("payment.amount", "99.99");
        paymentSpan.setAttribute("payment.currency", "USD");

        Thread.sleep(45); // payment processing

        // Grandchild span — payment-service calls fraud-service
        Span fraudSpan = tracer.spanBuilder("fraud-service/check")
                .setParent(paymentSpan)
                .startSpan();
        fraudSpan.setAttribute("fraud.score", "0.12");

        Thread.sleep(18); // fraud check
        fraudSpan.end();

        paymentSpan.end();

        Thread.sleep(5); // order persistence
        orderSpan.setAttribute("order.id", "ord-" + UUID.randomUUID().toString().substring(0, 8));
        orderSpan.end();

        // Print the trace tree
        tracer.printTrace();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PILLAR 3 — STRUCTURED LOGGING
    // CONCEPT: Structured logs are JSON — every field is queryable in Grafana Loki,
    // Elasticsearch, or Splunk. Unstructured logs ("Processing order 123") are
    // grep-only — they don't scale.
    //
    // WHY: The key insight is trace correlation: embedding traceId and spanId in
    // every log line lets you jump from a log entry directly to the Jaeger trace
    // for that specific request. This is what makes debugging a 5-service call
    // chain tractable.
    //
    // NOTE: Use a logging framework that supports MDC (Mapped Diagnostic Context):
    //   MDC.put("traceId", span.traceId());
    //   MDC.put("spanId", span.spanId());
    // With Logback + logstash-logback-encoder this outputs JSON automatically.
    // ─────────────────────────────────────────────────────────────────────────
    static void runStructuredLoggingDemo(SimulatedTracer tracer) {
        StructuredLogger logger = new StructuredLogger("order-service");

        // Simulate logging inside an active span
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        String spanId  = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        logger.info("Order received", traceId, spanId,
                Map.of("orderId", "ord-abc123", "userId", "usr-42", "amount", "99.99"));

        logger.info("Payment initiated", traceId, spanId,
                Map.of("paymentGateway", "stripe", "currency", "USD"));

        logger.warn("Retry attempt", traceId, spanId,
                Map.of("attempt", "2", "reason", "TIMEOUT", "retryAfterMs", "200"));

        logger.info("Order completed", traceId, spanId,
                Map.of("orderId", "ord-abc123", "durationMs", "312"));

        System.out.println("\n// In Grafana Loki you can now query:");
        System.out.println("// {service=\"order-service\"} | json | traceId=\"" + traceId + "\"");
        System.out.println("// Then click the traceId to jump directly to Jaeger trace.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REAL-WORLD: KAFKA CONSUMER LAG MONITORING
    // CONCEPT: Consumer lag = (latest offset) - (committed offset). It tells you
    // how far behind your consumer is from the head of the Kafka partition.
    //
    // WHY: Lag is the most important operational metric for Kafka. A growing lag
    // means your consumer can't keep up — this leads to stale data, SLO breaches,
    // and eventually OOM if unbounded buffering is used.
    //
    // NOTE: Alert on lag RATE OF CHANGE, not just the absolute value.
    //   - Lag=10000 but decreasing → consumer is catching up after a spike → OK
    //   - Lag=500 but increasing at 100/sec → consumer is falling behind → PAGE
    // ─────────────────────────────────────────────────────────────────────────
    static void runKafkaLagDemo(MicrometerRegistry registry) throws InterruptedException {
        System.out.println("Simulating Kafka consumer lag over time...\n");

        Gauge lagGauge0 = registry.gauge("kafka.consumer.lag",
                "group", "order-processor", "topic", "orders", "partition", "0");
        Gauge lagGauge1 = registry.gauge("kafka.consumer.lag",
                "group", "order-processor", "topic", "orders", "partition", "1");

        // Simulate: lag spikes then consumer catches up
        long[] lagScenario = {0, 120, 850, 3400, 5200, 4100, 2300, 900, 200, 0};

        System.out.printf("%-10s %-15s %-15s %-10s%n", "Time (s)", "Partition-0 Lag", "Partition-1 Lag", "Status");
        System.out.println("-".repeat(55));

        for (int i = 0; i < lagScenario.length; i++) {
            long p0Lag = lagScenario[i];
            long p1Lag = lagScenario[Math.min(i + 1, lagScenario.length - 1)];
            lagGauge0.set(p0Lag);
            lagGauge1.set(p1Lag);

            String status = (p0Lag > 2000 || p1Lag > 2000) ? "⚠ ALERT" : "OK";
            System.out.printf("%-10d %-15d %-15d %-10s%n", i * 10, p0Lag, p1Lag, status);
            Thread.sleep(100); // fast-forward for demo
        }

        System.out.println("\n// Prometheus query for lag alert:");
        System.out.println("// kafka_consumer_lag{group=\"order-processor\"} > 2000");
        System.out.println("// rate(kafka_consumer_lag{group=\"order-processor\"}[5m]) > 100");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SLO AND ERROR BUDGET
    // CONCEPT: An SLO (Service Level Objective) is a target reliability level,
    // e.g. "99.9% of /orders requests succeed within 200ms."
    // Error budget = 100% - SLO = the allowed failure headroom.
    //
    // WHY: Error budgets make the reliability vs. velocity trade-off concrete.
    // If the budget is spent, you stop shipping features and fix reliability.
    // If budget remains, you can take risks. Google SRE pioneered this.
    //
    // NOTE: SLOs must be defined from the USER perspective, not infra metrics.
    //   Bad SLO:  "CPU < 80%"             ← user doesn't care about CPU
    //   Good SLO: "p99 latency < 500ms"   ← user feels this directly
    // ─────────────────────────────────────────────────────────────────────────
    static void runSloDemo(MicrometerRegistry registry) {
        double sloTarget      = 99.9;          // 99.9% success rate
        long   windowSeconds  = 30 * 24 * 3600; // 30-day rolling window
        long   totalRequests  = 2_400_000;
        long   failedRequests = 1_850;          // simulated failures

        double successRate  = ((double)(totalRequests - failedRequests) / totalRequests) * 100;
        double errorBudgetSeconds = windowSeconds * (1.0 - sloTarget / 100);
        double errorBudgetUsed    = windowSeconds * (1.0 - successRate  / 100);
        double budgetRemainingPct = Math.max(0, (errorBudgetSeconds - errorBudgetUsed)
                                               / errorBudgetSeconds * 100);

        System.out.println("SLO Target         : " + sloTarget + "% success rate (30-day window)");
        System.out.printf( "Actual Success Rate: %.4f%%%n", successRate);
        System.out.printf( "Error Budget Total : %.0f seconds / month%n", errorBudgetSeconds);
        System.out.printf( "Error Budget Used  : %.0f seconds%n", errorBudgetUsed);
        System.out.printf( "Budget Remaining   : %.1f%%%n", budgetRemainingPct);
        System.out.println("SLO Status         : " + (successRate >= sloTarget ? "WITHIN SLO" : "SLO BREACH"));
        System.out.println();
        System.out.println("// If budget remaining < 10% → freeze non-critical releases");
        System.out.println("// If budget remaining < 0%  → incident review mandatory before next deploy");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SIMULATED MICROMETER REGISTRY
    // Mirrors the real io.micrometer.core.instrument.MeterRegistry API
    // ─────────────────────────────────────────────────────────────────────────
    static class MicrometerRegistry {
        private final Map<String, Counter> counters = new LinkedHashMap<>();
        private final Map<String, Timer>   timers   = new LinkedHashMap<>();
        private final Map<String, Gauge>   gauges   = new LinkedHashMap<>();

        Counter counter(String name, String... tags) {
            String key = name + Arrays.toString(tags);
            return counters.computeIfAbsent(key, k -> new Counter(name, tags));
        }

        Timer timer(String name, String... tags) {
            String key = name + Arrays.toString(tags);
            return timers.computeIfAbsent(key, k -> new Timer(name, tags));
        }

        Gauge gauge(String name, AtomicInteger value, String... tags) {
            Gauge g = new Gauge(name, value::get, tags);
            gauges.put(name + Arrays.toString(tags), g);
            return g;
        }

        Gauge gauge(String name, String... tags) {
            Gauge g = new Gauge(name, null, tags);
            gauges.put(name + Arrays.toString(tags), g);
            return g;
        }

        String scrape() {
            StringBuilder sb = new StringBuilder();
            counters.values().forEach(c -> sb.append(c.toPrometheus()).append("\n"));
            timers.values().forEach(t   -> sb.append(t.toPrometheus()).append("\n"));
            gauges.values().forEach(g   -> sb.append(g.toPrometheus()).append("\n"));
            return sb.toString();
        }
    }

    static class Counter {
        private final String name;
        private final String[] tags;
        private double value = 0;

        Counter(String name, String[] tags) { this.name = name; this.tags = tags; }
        void increment() { value++; }
        void increment(double amount) { value += amount; }
        double count() { return value; }

        String toPrometheus() {
            return prometheusName(name) + tagsToLabels(tags) + " " + (long) value;
        }
    }

    static class Timer {
        private final String name;
        private final String[] tags;
        private final List<Double> recordings = new ArrayList<>();

        Timer(String name, String[] tags) { this.name = name; this.tags = tags; }

        void record(Duration d) { recordings.add((double) d.toMillis()); }

        double meanMs() {
            return recordings.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
        double maxMs() {
            return recordings.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        }
        long count() { return recordings.size(); }

        String toPrometheus() {
            String n = prometheusName(name);
            String l = tagsToLabels(tags);
            return n + "_count" + l + " " + count() + "\n"
                 + n + "_sum"   + l + " " + (long) recordings.stream().mapToDouble(Double::doubleValue).sum() + "\n"
                 + n + "_max"   + l + " " + (long) maxMs();
        }
    }

    static class Gauge {
        private final String name;
        private final String[] tags;
        private java.util.function.LongSupplier supplier;
        private long value;

        Gauge(String name, java.util.function.LongSupplier supplier, String[] tags) {
            this.name = name; this.supplier = supplier; this.tags = tags;
        }
        void set(long v) { this.value = v; }
        String toPrometheus() {
            long v = supplier != null ? supplier.getAsLong() : value;
            return prometheusName(name) + tagsToLabels(tags) + " " + v;
        }
    }

    static String prometheusName(String name) { return name.replace(".", "_").replace("-", "_"); }

    static String tagsToLabels(String[] tags) {
        if (tags == null || tags.length == 0) return "";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < tags.length - 1; i += 2) {
            if (i > 0) sb.append(",");
            sb.append(tags[i]).append("=\"").append(tags[i + 1]).append("\"");
        }
        return sb.append("}").toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SIMULATED OPENTELEMETRY TRACER
    // Mirrors the real io.opentelemetry.api.trace.Tracer API
    // ─────────────────────────────────────────────────────────────────────────
    static class SimulatedTracer {
        private final String serviceName;
        private final List<Span> spans = new ArrayList<>();
        private final String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 32);

        SimulatedTracer(String serviceName) { this.serviceName = serviceName; }

        SpanBuilder spanBuilder(String name) { return new SpanBuilder(name, this); }

        void register(Span span) { spans.add(span); }

        void printTrace() {
            System.out.println("Trace ID: " + traceId);
            System.out.println("Service : " + serviceName);
            System.out.println();
            spans.stream()
                 .sorted(Comparator.comparing(s -> s.startTime))
                 .forEach(s -> {
                     String indent = "  ".repeat(s.depth);
                     System.out.printf("%s[%s] %s — %d ms%n",
                             indent, s.spanId.substring(0, 8), s.name, s.durationMs());
                     s.attributes.forEach((k, v) ->
                             System.out.printf("%s  %s: %s%n", indent, k, v));
                 });
        }
    }

    static class SpanBuilder {
        private final String name;
        private final SimulatedTracer tracer;
        private Span parent;

        SpanBuilder(String name, SimulatedTracer tracer) {
            this.name = name; this.tracer = tracer;
        }
        SpanBuilder setParent(Span parent) { this.parent = parent; return this; }
        Span startSpan() {
            int depth = parent == null ? 0 : parent.depth + 1;
            Span span = new Span(name, UUID.randomUUID().toString().replace("-","").substring(0,16), depth);
            tracer.register(span);
            return span;
        }
    }

    static class Span {
        final String name;
        final String spanId;
        final int depth;
        final Instant startTime = Instant.now();
        final Map<String, String> attributes = new LinkedHashMap<>();
        private Instant endTime;

        Span(String name, String spanId, int depth) {
            this.name = name; this.spanId = spanId; this.depth = depth;
        }
        void setAttribute(String key, String value) { attributes.put(key, value); }
        void end() { this.endTime = Instant.now(); }
        long durationMs() {
            return Duration.between(startTime, endTime == null ? Instant.now() : endTime).toMillis();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STRUCTURED LOGGER
    // Simulates JSON log output with trace correlation (what Logback + MDC does)
    // ─────────────────────────────────────────────────────────────────────────
    static class StructuredLogger {
        private final String service;
        StructuredLogger(String service) { this.service = service; }

        void info(String message, String traceId, String spanId, Map<String, String> fields) {
            log("INFO", message, traceId, spanId, fields);
        }
        void warn(String message, String traceId, String spanId, Map<String, String> fields) {
            log("WARN", message, traceId, spanId, fields);
        }

        private void log(String level, String message, String traceId, String spanId, Map<String, String> fields) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"timestamp\":\"").append(Instant.now()).append("\",");
            sb.append("\"level\":\"").append(level).append("\",");
            sb.append("\"service\":\"").append(service).append("\",");
            sb.append("\"traceId\":\"").append(traceId).append("\",");
            sb.append("\"spanId\":\"").append(spanId).append("\",");
            sb.append("\"message\":\"").append(message).append("\"");
            fields.forEach((k, v) -> sb.append(",\"").append(k).append("\":\"").append(v).append("\""));
            sb.append("}");
            System.out.println(sb);
        }
    }
}
