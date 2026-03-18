# CLAUDE.md — Java Deep Dive

## Project Overview

A 6-month, hands-on Java learning project targeting **Staff Engineer level** (Netflix/Google). Each month covers a distinct domain with runnable demo files that explain the what, why, and trade-offs.

## Tech Stack

- **Java 21+**
- **Gradle 8+** (multi-module build)
- Kafka, gRPC, Kubernetes client, Reactive Streams, JWT/AES/RSA — per module

## Module Structure

```
month-01-java-foundations/     ← JVM internals, concurrency, NIO
month-02-cloud-native/         ← Microservices, Kafka, gRPC, CQRS
month-03-distributed-systems/  ← CAP, Raft, sharding, circuit breakers
month-04-kubernetes/           ← K8s client, graceful shutdown, RBAC
month-05-real-time-systems/    ← Kafka Streams, reactive, Disruptor
month-06-security-leadership/  ← JWT, AES/RSA, zero trust, ADRs
```

## Build & Run

```bash
# Build all modules
./gradlew build

# Run a specific demo
./gradlew :month-01-java-foundations:run -PmainClass=com.deepdive.month01.week03.ForkJoinDemo

# Compile a single module
./gradlew :month-01-java-foundations:compileJava
```

## Code Conventions

Every demo file uses these comment markers:
- `// CONCEPT:` — What the pattern/technology is
- `// WHY:` — Why it matters at Staff Engineer level
- `// NOTE:` — Gotchas and production considerations

Follow this pattern consistently when adding new demo files.

## External Dependencies

- **Kafka demos** require a running Kafka instance:
  ```bash
  docker run -d -p 9092:9092 apache/kafka:latest
  ```

## Goal

Understand trade-offs well enough to explain and defend decisions in system design interviews and RFC reviews — not just run the code.
