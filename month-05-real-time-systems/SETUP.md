# Month 5 — Real-Time Systems: Setup Guide

## Dependencies

Uncomment in `month-05-real-time-systems/build.gradle` based on what you want to run:

```gradle
dependencies {
    // Project Reactor — reactive programming (Mono/Flux)
    implementation 'io.projectreactor:reactor-core:3.6.1'
    implementation 'io.projectreactor.netty:reactor-netty-core:1.1.14'

    // Kafka Streams — stateful stream processing
    implementation 'org.apache.kafka:kafka-streams:3.6.1'
    implementation 'org.apache.kafka:kafka-clients:3.6.1'

    // Spring WebFlux — reactive HTTP client
    implementation 'org.springframework.boot:spring-boot-starter-webflux:3.2.1'
}
```

> **Note:** All demos run as-is using simulated implementations. Uncomment dependencies to connect to real Kafka or use real Reactor operators.

---

## External Tools

### 1. Apache Kafka
> Used with: `KafkaStreamsDemo` (Week 17)

**Docker (KRaft mode — no ZooKeeper):**
```bash
docker run -d \
  --name kafka \
  -p 9092:9092 \
  apache/kafka:latest

# Verify
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

**Create topics for Kafka Streams:**
```bash
# Input topic
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic orders-input \
  --partitions 3 --replication-factor 1

# Output topic
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic orders-output \
  --partitions 3 --replication-factor 1
```

**Monitor Kafka Streams application lag:**
```bash
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group streams-app
```

---

### 2. Kafka Streams State Store Inspection
> Kafka Streams uses local RocksDB for state — inspect during Week 17

```bash
# List Kafka Streams internal topics (state store changelogs)
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list | grep "streams"

# Watch windowed aggregation output
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic orders-output \
  --from-beginning \
  --formatter kafka.tools.DefaultMessageFormatter \
  --property print.key=true
```

---

### 3. Project Reactor Tools
> Used with: `ReactiveStreamsDemo`, `BackpressureDemo`, `ReactiveWebClientDemo` (Week 18–19)

**Add Reactor test utilities to build.gradle:**
```gradle
dependencies {
    implementation 'io.projectreactor:reactor-core:3.6.1'

    // For testing reactive pipelines
    testImplementation 'io.projectreactor:reactor-test:3.6.1'
}
```

**Enable Reactor debug mode (adds assembly traceability):**
```java
// Add at application startup — shows full reactive chain in stack traces
Hooks.onOperatorDebug();

// Or use checkpoint for targeted debugging
Flux.range(1, 10)
    .map(i -> i * 2)
    .checkpoint("after-map")
    .subscribe();
```

**BlockHound — detect blocking calls in reactive pipelines:**
```gradle
testImplementation 'io.projectreactor.tools:blockhound:1.0.8.RELEASE'
```
```java
// Install at test startup
BlockHound.install();
// Now Thread.sleep() or any blocking call inside a Reactor scheduler will throw
```

---

### 4. LMAX Disruptor
> Used with: `DisruptorPatternDemo` (Week 20) — the demo simulates the Disruptor ring buffer

**Add real Disruptor library:**
```gradle
dependencies {
    implementation 'com.lmax:disruptor:3.4.4'
}
```

**Real Disruptor setup pattern:**
```java
// Pre-allocate ring buffer (must be power of 2)
Disruptor<OrderEvent> disruptor = new Disruptor<>(
    OrderEvent::new,       // event factory
    1024,                  // ring buffer size (power of 2)
    DaemonThreadFactory.INSTANCE,
    ProducerType.SINGLE,
    new BusySpinWaitStrategy()  // lowest latency, highest CPU
);

disruptor.handleEventsWith(new OrderEventHandler());
RingBuffer<OrderEvent> ringBuffer = disruptor.start();
```

**Wait strategies comparison:**

| Strategy | Latency | CPU Usage | Use Case |
|----------|---------|-----------|----------|
| `BusySpinWaitStrategy` | Lowest | Highest (100%) | Ultra-low latency, dedicated core |
| `YieldingWaitStrategy` | Low | High | Low latency with some CPU sharing |
| `SleepingWaitStrategy` | Medium | Low | Balanced — good default |
| `BlockingWaitStrategy` | Highest | Lowest | Throughput over latency |

---

### 5. async-profiler (Week 20 — high-frequency trading patterns)
> Profile CPU flamegraphs for `HighFrequencyDemo` — see false sharing, memory access patterns

```bash
# macOS
brew install async-profiler

# Profile HighFrequencyDemo for 10 seconds
./gradlew :month-05-real-time-systems:run \
  -PmainClass=com.deepdive.month05.week20.HighFrequencyDemo &
PID=$!
asprof -d 10 -f hf-flamegraph.html $PID
open hf-flamegraph.html
```

---

### 6. Grafana + Prometheus (optional — observability for streams)

```bash
# Start Prometheus + Grafana with Docker Compose
cat <<EOF > docker-compose-obs.yml
version: '3'
services:
  prometheus:
    image: prom/prometheus:latest
    ports: ["9090:9090"]
  grafana:
    image: grafana/grafana:latest
    ports: ["3000:3000"]
EOF

docker-compose -f docker-compose-obs.yml up -d
# Grafana: http://localhost:3000 (admin/admin)
# Prometheus: http://localhost:9090
```

---

## Quick Start

```bash
# 1. Start Kafka (for KafkaStreamsDemo)
docker run -d --name kafka -p 9092:9092 apache/kafka:latest

# 2. Compile the module
./gradlew :month-05-real-time-systems:compileJava

# 3. Run Kafka Streams demo
./gradlew :month-05-real-time-systems:run \
  -PmainClass=com.deepdive.month05.week17.KafkaStreamsDemo

# 4. Run reactive streams demo (no external deps)
./gradlew :month-05-real-time-systems:run \
  -PmainClass=com.deepdive.month05.week18.ReactiveStreamsDemo

# 5. Run backpressure demo
./gradlew :month-05-real-time-systems:run \
  -PmainClass=com.deepdive.month05.week18.BackpressureDemo

# 6. Run high-frequency / false sharing demo
./gradlew :month-05-real-time-systems:run \
  -PmainClass=com.deepdive.month05.week20.HighFrequencyDemo
```
