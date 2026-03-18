# Month 2 — Cloud-Native & Microservices: Setup Guide

## Dependencies

Uncomment in `month-02-cloud-native/build.gradle` based on what you want to run:

```gradle
dependencies {
    // Spring Boot — web framework for microservices
    implementation 'org.springframework.boot:spring-boot-starter-web:3.2.1'
    implementation 'org.springframework.boot:spring-boot-starter-actuator:3.2.1'

    // Kafka — event-driven architecture
    implementation 'org.springframework.kafka:spring-kafka:3.1.0'
    implementation 'org.apache.kafka:kafka-clients:3.6.1'

    // gRPC — high-performance RPC
    implementation 'io.grpc:grpc-netty-shaded:1.60.0'
    implementation 'io.grpc:grpc-protobuf:1.60.0'
    implementation 'io.grpc:grpc-stub:1.60.0'
}
```

> **Note:** All demos run as-is without uncommenting — they use simulated implementations for learning. Uncomment dependencies when you want to connect to real services.

---

## External Tools

### 1. Apache Kafka
> Used with: `KafkaProducerDemo`, `KafkaConsumerDemo` (Week 7)

**Option A — Docker (recommended, quickest)**
```bash
# Start Kafka (KRaft mode — no ZooKeeper needed)
docker run -d \
  --name kafka \
  -p 9092:9092 \
  apache/kafka:latest

# Verify it's running
docker logs kafka | grep "started"
```

**Option B — Docker Compose (multi-broker setup)**
```yaml
# docker-compose.yml
version: '3'
services:
  kafka:
    image: apache/kafka:latest
    ports:
      - "9092:9092"
    environment:
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_NODE_ID: 1
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
```
```bash
docker-compose up -d
```

**Useful Kafka CLI commands:**
```bash
# Create a topic
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic orders --partitions 3 --replication-factor 1

# List topics
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list

# Watch messages on a topic
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic orders --from-beginning

# Check consumer group lag
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group my-group
```

---

### 2. Kafka UI (optional — visual dashboard)
> Browse topics, messages, consumer groups, and lag in a browser

```bash
docker run -d \
  --name kafka-ui \
  -p 8080:8080 \
  -e KAFKA_CLUSTERS_0_NAME=local \
  -e KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=host.docker.internal:9092 \
  provectuslabs/kafka-ui:latest

# Open: http://localhost:8080
```

---

### 3. gRPC Tools
> Used with: `GrpcConceptsDemo` (Week 6)

**Install grpcurl (CLI client for gRPC):**
```bash
# macOS
brew install grpcurl

# Test a gRPC endpoint
grpcurl -plaintext localhost:50051 list
grpcurl -plaintext -d '{"name": "World"}' localhost:50051 helloworld.Greeter/SayHello
```

**Protobuf compiler:**
```bash
# macOS
brew install protobuf

# Verify
protoc --version
```

**Add to build.gradle for real gRPC with protobuf codegen:**
```gradle
plugins {
    id 'com.google.protobuf' version '0.9.4'
}

protobuf {
    protoc { artifact = 'com.google.protobuf:protoc:3.25.1' }
    plugins {
        grpc { artifact = 'io.grpc:protoc-gen-grpc-java:1.60.0' }
    }
    generateProtoTasks {
        all()*.plugins { grpc {} }
    }
}
```

---

### 4. Postman / HTTPie (API testing)
> Used with: `ApiVersioningDemo` (Week 8)

```bash
# Install HTTPie (lightweight CLI HTTP client)
brew install httpie

# Test versioned endpoints
http GET localhost:8080/api/users/1 Accept:application/vnd.api.v1+json
http GET localhost:8080/api/v2/users/1
http GET localhost:8080/api/v3/users/1
```

---

## Quick Start

```bash
# 1. Start Kafka
docker run -d --name kafka -p 9092:9092 apache/kafka:latest

# 2. Compile the module
./gradlew :month-02-cloud-native:compileJava

# 3. Run CQRS demo (no external deps needed)
./gradlew :month-02-cloud-native:run \
  -PmainClass=com.deepdive.month02.week05.CqrsDemo

# 4. Run Kafka producer demo (requires Kafka running)
./gradlew :month-02-cloud-native:run \
  -PmainClass=com.deepdive.month02.week07.KafkaProducerDemo

# 5. Run Kafka consumer demo (in a separate terminal)
./gradlew :month-02-cloud-native:run \
  -PmainClass=com.deepdive.month02.week07.KafkaConsumerDemo
```
